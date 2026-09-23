package com.example.ssds.api.schedule;

import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.infra.entity.HeatReading;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.ingest.HeatDataPoint;
import com.example.ssds.ingest.GoogleTrends.GoogleTrendsHeatSourceAdapter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
        name = "ssds.ingest.google-trends.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class GoogleTrendsHeatIngestJob {

    private static final Logger log = LoggerFactory.getLogger(GoogleTrendsHeatIngestJob.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final TrendKeywordRepository trendKeywordRepository;
    private final HeatSourceRepository heatSourceRepository;
    private final HeatReadingRepository heatReadingRepository;
    private final GoogleTrendsHeatSourceAdapter trendsAdapter;

    public GoogleTrendsHeatIngestJob(
            TrendKeywordRepository trendKeywordRepository,
            HeatSourceRepository heatSourceRepository,
            HeatReadingRepository heatReadingRepository,
            GoogleTrendsHeatSourceAdapter trendsAdapter) {
        this.trendKeywordRepository = trendKeywordRepository;
        this.heatSourceRepository = heatSourceRepository;
        this.heatReadingRepository = heatReadingRepository;
        this.trendsAdapter = trendsAdapter;
    }

    @Scheduled(cron = "${ssds.ingest.google-trends.cron:0 15 3 * * *}", zone = "Asia/Taipei")
    @Transactional
    public void run() {
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.GOOGLE_TRENDS).orElse(null);
        if (source == null) {
            log.warn("heat_source 尚未註冊 GOOGLE_TRENDS 這筆，略過採集。");
            return;
        }
        if (!source.isEnabled()) {
            log.info("GOOGLE_TRENDS 來源已停用（enabled=false），略過採集。");
            return;
        }

        List<TrendKeyword> keywords = trendKeywordRepository.findByEnabledTrue();
        if (keywords.isEmpty()) {
            log.info("沒有啟用中的關鍵字，略過 Google Trends 採集。");
            return;
        }

        LocalDate today = LocalDate.now(TAIPEI);
        List<String> keywordTexts = keywords.stream().map(TrendKeyword::getKeyword).toList();

        List<HeatDataPoint> points;
        try {
            points = trendsAdapter.fetch(keywordTexts, today);
        } catch (Exception e) {
            log.error("Google Trends 熱度採集整批失敗，來源標記為 UNAVAILABLE", e);
            source.setAvailability(SourceAvailability.UNAVAILABLE);
            heatSourceRepository.save(source);
            return;
        }

        for (HeatDataPoint point : points) {
            keywords.stream()
                    .filter(k -> k.getKeyword().equals(point.target()))
                    .findFirst()
                    .ifPresent(k -> upsert(source, k, today, point));
        }

        source.setAvailability(points.isEmpty() ? SourceAvailability.DEGRADED : SourceAvailability.AVAILABLE);
        source.setLastFetchedAt(Instant.now());
        source.setQuotaUsed(source.getQuotaUsed() + keywordTexts.size());
        heatSourceRepository.save(source);

        log.info("Google Trends 熱度採集完成：查詢 {} 個關鍵字，取得 {} 筆讀值。", keywordTexts.size(), points.size());
    }

    private void upsert(HeatSource source, TrendKeyword keyword, LocalDate date, HeatDataPoint point) {
        HeatReading reading = heatReadingRepository
                .findByKeywordIdAndSourceIdAndReadingDate(keyword.getId(), source.getId(), date)
                .orElseGet(() -> HeatReading.builder().source(source).keyword(keyword).readingDate(date).build());
        reading.setRawValue(point.rawValue());
        heatReadingRepository.save(reading);
    }
}
