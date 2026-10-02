package com.example.ssds.api.schedule;

import com.example.ssds.api.heat.HeatSourceQuota;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.HeatReading;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.entity.InstagramHashtagMapping;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.InstagramHashtagMappingRepository;
import com.example.ssds.ingest.HeatDataPoint;
import com.example.ssds.ingest.Instagram.InstagramHeatSourceAdapter;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每日採集 Instagram hashtag 熱度，落地到 {@code heat_reading}（品類級，
 * 見 §7.2.3 V17 裁決）。hashtag→品類對照存於
 * {@link InstagramHashtagMapping}（V29：資料庫表，直接 FK 關聯品類，
 * 理由見該類別的類別註解）。
 *
 * <p>只寫入 {@code raw_value}；同來源內百分位化
 * （{@code percentile_within_source}）是跨品類的批次計算，由既有的
 * 百分位化批次任務另外處理，本檔不涉及。
 *
 * <p>目前單一 app 實例執行，未加分散式鎖（見 {@code SchedulingConfig} 說明）。
 */
@Component
@ConditionalOnProperty(
        name = "ssds.ingest.instagram.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class InstagramHeatIngestJob {

    private static final Logger log = LoggerFactory.getLogger(InstagramHeatIngestJob.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final HeatSourceRepository heatSourceRepository;
    private final HeatReadingRepository heatReadingRepository;
    private final InstagramHashtagMappingRepository instagramHashtagMappingRepository;
    private final InstagramHeatSourceAdapter instagramAdapter;

    public InstagramHeatIngestJob(
            HeatSourceRepository heatSourceRepository,
            HeatReadingRepository heatReadingRepository,
            InstagramHashtagMappingRepository instagramHashtagMappingRepository,
            InstagramHeatSourceAdapter instagramAdapter) {
        this.heatSourceRepository = heatSourceRepository;
        this.heatReadingRepository = heatReadingRepository;
        this.instagramHashtagMappingRepository = instagramHashtagMappingRepository;
        this.instagramAdapter = instagramAdapter;
    }

    @Scheduled(cron = "${ssds.ingest.instagram.cron:0 30 3 * * MON}", zone = "Asia/Taipei")
    @Transactional
    public void run() {
        runMissingForWeek(LocalDate.now(TAIPEI));
    }

    /**
     * 只採集指定營業日所在週、截至該日仍無 reading 的啟用品類。
     *
     * @return 是否新增或更新了至少一筆缺漏品類 reading
     */
    @Transactional
    public boolean runMissingForWeek(LocalDate businessDate) {
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.INSTAGRAM).orElse(null);
        if (source == null) {
            log.warn("heat_source 找不到代碼 INSTAGRAM 這筆，放棄本次執行。");
            return false;
        }
        if (!source.isEnabled()) {
            log.info("INSTAGRAM 資料來源已停用（enabled=false），放棄本次執行。");
            return false;
        }

        List<InstagramHashtagMapping> mappings = instagramHashtagMappingRepository.findAllEnabledWithCategory();
        if (mappings.isEmpty()) {
            log.info("InstagramHashtagMapping 沒有啟用中的 hashtag，放棄本次執行。");
            return false;
        }

        LocalDate weekStart = businessDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Set<Long> completedCategoryIds = heatReadingRepository.findCategoryIdsWithReadingBetween(
                HeatSourceCode.INSTAGRAM, weekStart, businessDate);
        mappings = mappings.stream()
                .filter(mapping -> !completedCategoryIds.contains(mapping.getCategory().getId()))
                .toList();
        if (mappings.isEmpty()) {
            log.info("Instagram 本週截至 {} 的啟用品類皆已有資料，不需重複採集。", businessDate);
            return false;
        }

        // 採集前先讀 Apify 最新用量：本月額度已用完就不再呼叫（enabled 仍保持使用者設定，月初重置後自動恢復）
        if (!HeatSourceQuota.hasRoom(source, instagramAdapter)) {
            log.warn("INSTAGRAM 本月 Apify 額度已用完（{}/{} 美分），略過採集。",
                    source.getQuotaUsed(), source.getQuotaLimit());
            // 略過本次採集：標 UNAVAILABLE（不動 enabled），並保存剛讀到的最新用量；下月額度重置後自動恢復
            source.markQuotaExhausted();
            heatSourceRepository.save(source);
            return false;
        }

        List<String> hashtags = mappings.stream().map(InstagramHashtagMapping::getHashtag).toList();

        List<HeatDataPoint> points;
        try {
            points = instagramAdapter.fetch(hashtags, businessDate);
        } catch (Exception e) {
            boolean currentWeekReadingExists = hasCurrentWeekReading(businessDate);
            SourceAvailability failureAvailability = currentWeekReadingExists
                    ? SourceAvailability.DEGRADED
                    : SourceAvailability.UNAVAILABLE;
            log.error(
                    "Instagram 熱度採集發生錯誤，資料來源狀態改為 {}",
                    failureAvailability,
                    e);
            source.setAvailability(failureAvailability);
            heatSourceRepository.save(source);
            return false;
        }

        int persisted = 0;
        for (HeatDataPoint point : points) {
            InstagramHashtagMapping mapping = mappings.stream()
                    .filter(m -> m.getHashtag().equals(point.target()))
                    .findFirst()
                    .orElse(null);
            if (mapping != null) {
                upsert(source, mapping.getCategory(), businessDate, point);
                persisted++;
            }
        }

        source.setLastFetchedAt(Instant.now());
        // 額度改讀 Apify 後台的本月用量（不再自行累加關鍵字數，單位不同）
        HeatSourceQuota.refresh(source, instagramAdapter);
        // 狀態統一走 HeatSource 的判定（額度 ≥80% 降級、100% 不可用），不再只看有沒有資料
        source.applyIngestResult(!points.isEmpty(), businessDate);
        heatSourceRepository.save(source);

        log.info(
                "Instagram 缺漏品類採集完成，採集 {} 個 hashtag，取得 {} 筆讀值、寫入 {} 筆。",
                hashtags.size(),
                points.size(),
                persisted);
        return persisted > 0;
    }

    /** 本週已有有效快照時，修復舊版失敗流程留下的 UNAVAILABLE，保留為可降級使用。 */
    @Transactional
    public boolean restoreAvailabilityFromCurrentWeek(LocalDate businessDate) {
        if (!hasCurrentWeekReading(businessDate)) {
            return false;
        }
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.INSTAGRAM).orElse(null);
        if (source == null
                || !source.isEnabled()
                || source.getAvailability() != SourceAvailability.UNAVAILABLE) {
            return false;
        }
        source.setAvailability(SourceAvailability.DEGRADED);
        heatSourceRepository.save(source);
        log.info("Instagram 本週 reading 仍有效，來源狀態由 UNAVAILABLE 修復為 DEGRADED。");
        return true;
    }

    private boolean hasCurrentWeekReading(LocalDate businessDate) {
        LocalDate weekStart = businessDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return heatReadingRepository.existsBySourceSourceCodeAndReadingDateBetween(
                HeatSourceCode.INSTAGRAM, weekStart, weekStart.plusDays(6));
    }

    private void upsert(HeatSource source, Category category, LocalDate date, HeatDataPoint point) {
        HeatReading reading = heatReadingRepository
                .findByCategoryIdAndSourceIdAndReadingDate(category.getId(), source.getId(), date)
                .orElseGet(() -> HeatReading.builder().source(source).category(category).readingDate(date).build());
        reading.setRawValue(point.rawValue());
        heatReadingRepository.save(reading);
    }
}
