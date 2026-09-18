package com.example.ssds.api.schedule;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.INSTAGRAM).orElse(null);
        if (source == null) {
            log.warn("heat_source 找不到代碼 INSTAGRAM 這筆，放棄本次執行。");
            return;
        }
        if (!source.isEnabled()) {
            log.info("INSTAGRAM 資料來源已停用（enabled=false），放棄本次執行。");
            return;
        }

        List<InstagramHashtagMapping> mappings = instagramHashtagMappingRepository.findAllEnabledWithCategory();
        if (mappings.isEmpty()) {
            log.info("InstagramHashtagMapping 沒有啟用中的 hashtag，放棄本次執行。");
            return;
        }

        LocalDate today = LocalDate.now(TAIPEI);
        List<String> hashtags = mappings.stream().map(InstagramHashtagMapping::getHashtag).toList();

        List<HeatDataPoint> points;
        try {
            points = instagramAdapter.fetch(hashtags, today);
        } catch (Exception e) {
            // 對應錯誤處理：token 過期／速率限制等不予重試，見 §5.3.2 裁決，
            // 把這次資料來源狀態改成 UNAVAILABLE 後直接結束，不阻塞後續排程。
            log.error("Instagram 熱度採集發生錯誤，資料來源狀態改為 UNAVAILABLE", e);
            source.setAvailability(SourceAvailability.UNAVAILABLE);
            heatSourceRepository.save(source);
            return;
        }

        for (HeatDataPoint point : points) {
            mappings.stream()
                    .filter(m -> m.getHashtag().equals(point.target()))
                    .findFirst()
                    .ifPresent(m -> upsert(source, m.getCategory(), today, point));
        }

        source.setAvailability(points.isEmpty() ? SourceAvailability.DEGRADED : SourceAvailability.AVAILABLE);
        source.setLastFetchedAt(Instant.now());
        source.setQuotaUsed(source.getQuotaUsed() + hashtags.size());
        heatSourceRepository.save(source);

        log.info("Instagram 熱度採集完成，採集 {} 個 hashtag，取得 {} 筆讀值。", hashtags.size(), points.size());
    }

    private void upsert(HeatSource source, Category category, LocalDate date, HeatDataPoint point) {
        HeatReading reading = heatReadingRepository
                .findByCategoryIdAndSourceIdAndReadingDate(category.getId(), source.getId(), date)
                .orElseGet(() -> HeatReading.builder().source(source).category(category).readingDate(date).build());
        reading.setRawValue(point.rawValue());
        heatReadingRepository.save(reading);
    }
}