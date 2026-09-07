package com.example.ssds.api.schedule;

import com.example.ssds.api.config.InstagramHashtagMapping;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.HeatReading;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.ingest.HeatDataPoint;
import com.example.ssds.ingest.Instagram.InstagramHeatSourceAdapter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每日採集 Instagram hashtag 熱度，落地到 {@code heat_reading}（品類級，
 * 見 §7.2.3 V17 裁決）。hashtag→品類對照寫死在
 * {@link InstagramHashtagMapping}（純 Java 常數，不是設定檔也不是資料庫欄位——
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

    private final CategoryRepository categoryRepository;
    private final HeatSourceRepository heatSourceRepository;
    private final HeatReadingRepository heatReadingRepository;
    private final InstagramHeatSourceAdapter instagramAdapter;

    public InstagramHeatIngestJob(
            CategoryRepository categoryRepository,
            HeatSourceRepository heatSourceRepository,
            HeatReadingRepository heatReadingRepository,
            InstagramHeatSourceAdapter instagramAdapter) {
        this.categoryRepository = categoryRepository;
        this.heatSourceRepository = heatSourceRepository;
        this.heatReadingRepository = heatReadingRepository;
        this.instagramAdapter = instagramAdapter;
    }

    /**
     * 每週一台北時間 03:30 執行一次（規格「資料來源界定」表：Instagram 頻率為每週）。
     *
     * <p>額度提醒：改用 Apify 的 apify/instagram-hashtag-scraper 後是依
     * 「實際爬到的貼文數」計費，每次執行會消耗
     * {@code InstagramHashtagMapping.ENTRIES.size()} × resultsLimit 篇份的
     * 用量。品類數量或 resultsLimit 若之後明顯增加，要留意 Apify 帳號的
     * 用量額度／預算（見 CONTEXT.md §7）。
     */
    @Scheduled(cron = "${ssds.ingest.instagram.cron:0 30 3 * * MON}", zone = "Asia/Taipei")
    @Transactional
    public void run() {
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.INSTAGRAM).orElse(null);
        if (source == null) {
            log.warn("heat_source 尚未註冊 INSTAGRAM 這筆，略過採集。");
            return;
        }
        if (!source.isEnabled()) {
            log.info("INSTAGRAM 來源已停用（enabled=false），略過採集。");
            return;
        }
        if (InstagramHashtagMapping.ENTRIES.isEmpty()) {
            log.info("InstagramHashtagMapping.ENTRIES 未設定任何 hashtag，略過採集。");
            return;
        }

        // 解析寫死在 InstagramHashtagMapping 的「hashtag → 品類名稱」，查到重複／
        // 查不到的品類名稱只跳過該筆並記警告，不讓整個排程失敗。
        List<HashtagCategory> resolved = new ArrayList<>();
        for (InstagramHashtagMapping.Entry entry : InstagramHashtagMapping.ENTRIES) {
            List<Category> matches = categoryRepository.findByNameIgnoreCase(entry.categoryName());
            if (matches.isEmpty()) {
                log.warn("設定檔的品類名稱查無對應品類，跳過：{}", entry.categoryName());
                continue;
            }
            if (matches.size() > 1) {
                log.warn("設定檔的品類名稱對應到多筆品類，跳過（請改用更精確的名稱）：{}", entry.categoryName());
                continue;
            }
            resolved.add(new HashtagCategory(entry.hashtag(), matches.get(0)));
        }
        if (resolved.isEmpty()) {
            log.warn("設定檔裡的品類名稱一筆都解析不到，略過本次採集。");
            return;
        }

        LocalDate today = LocalDate.now(TAIPEI);
        List<String> hashtags = resolved.stream().map(HashtagCategory::hashtag).toList();

        List<HeatDataPoint> points;
        try {
            points = instagramAdapter.fetch(hashtags, today);
        } catch (Exception e) {
            // 整批失敗（token 失效、完全連不上等）：依 §5.3.2 降級，
            // 標記來源不可用、權重歸零重新正規化，不阻斷評分流程。
            log.error("Instagram 熱度採集整批失敗，來源標記為 UNAVAILABLE", e);
            source.setAvailability(SourceAvailability.UNAVAILABLE);
            heatSourceRepository.save(source);
            return;
        }

        for (HeatDataPoint point : points) {
            resolved.stream()
                    .filter(hc -> hc.hashtag().equals(point.target()))
                    .findFirst()
                    .ifPresent(hc -> upsert(source, hc.category(), today, point));
        }

        // 全部 target 都查不到資料視為 DEGRADED（來源還在，但這次沒收到任何讀值），
        // 不是 UNAVAILABLE——那個狀態保留給「整批呼叫直接失敗」。
        source.setAvailability(points.isEmpty() ? SourceAvailability.DEGRADED : SourceAvailability.AVAILABLE);
        source.setLastFetchedAt(Instant.now());
        source.setQuotaUsed(source.getQuotaUsed() + hashtags.size());
        heatSourceRepository.save(source);

        log.info("Instagram 熱度採集完成：查詢 {} 個 hashtag，取得 {} 筆讀值。", hashtags.size(), points.size());
    }

    private void upsert(HeatSource source, Category category, LocalDate date, HeatDataPoint point) {
        HeatReading reading = heatReadingRepository
                .findByCategoryIdAndSourceIdAndReadingDate(category.getId(), source.getId(), date)
                .orElseGet(() -> HeatReading.builder().source(source).category(category).readingDate(date).build());
        reading.setRawValue(point.rawValue());
        heatReadingRepository.save(reading);
    }

    private record HashtagCategory(String hashtag, Category category) {}
}