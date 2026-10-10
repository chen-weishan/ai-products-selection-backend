package com.example.ssds.api.schedule;

import com.example.ssds.api.heat.HeatSourceQuota;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.infra.entity.HeatReading;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.infra.dao.HeatReadingPercentileDao;
import com.example.ssds.infra.service.HeatCompositeCalibrationService;
import com.example.ssds.ingest.GoogleTrends.GoogleTrendsHeatSourceAdapter;
import com.example.ssds.ingest.GoogleTrends.GoogleTrendsClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoogleTrendsBackfillService {

    private static final Logger log = LoggerFactory.getLogger(GoogleTrendsBackfillService.class);

    private final GoogleTrendsClient client;
    private final HeatSourceRepository heatSourceRepository;
    private final HeatReadingRepository heatReadingRepository;
    private final HeatReadingPercentileDao percentileDao;
    private final HeatCompositeCalibrationService calibrationService;
    private final TrendKeywordRepository trendKeywordRepository;

    public GoogleTrendsBackfillService(
            GoogleTrendsClient client,
            HeatSourceRepository heatSourceRepository,
            HeatReadingRepository heatReadingRepository,
            HeatReadingPercentileDao percentileDao,
            HeatCompositeCalibrationService calibrationService,
            TrendKeywordRepository trendKeywordRepository) {
        this.client = client;
        this.heatSourceRepository = heatSourceRepository;
        this.heatReadingRepository = heatReadingRepository;
        this.percentileDao = percentileDao;
        this.calibrationService = calibrationService;
        this.trendKeywordRepository = trendKeywordRepository;
    }

    @Transactional
    public void backfillKeyword(Long keywordId, String timeframe) {
        List<LocalDate> touchedDates = backfillKeywordReadings(
                keywordId, timeframe, LocalDate.MIN, LocalDate.MAX);
        touchedDates.forEach(date -> {
            percentileDao.applyPercentiles(date);
            calibrationService.computeAndPersist(keywordId, date);
        });
    }

    /** 僅回補指定日期範圍的讀值；百分位與合成由呼叫端在所有來源寫入後統一處理。 */
    @Transactional
    public List<LocalDate> backfillKeywordReadings(
            Long keywordId, String timeframe, LocalDate from, LocalDate to) {
        TrendKeyword keyword = trendKeywordRepository.getReferenceById(keywordId);
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.GOOGLE_TRENDS)
                .orElseThrow();
        // 回補不經過 ingest job，要自己擋：來源被停用或本月額度已用完時不打 Apify（只看資料庫記的用量，不連網）
        if (!source.isEnabled()) {
            log.info("GOOGLE_TRENDS 來源已停用（enabled=false），略過回補。");
            return List.of();
        }
        if (HeatSourceQuota.isExhausted(source)) {
            log.warn("GOOGLE_TRENDS 本月 Apify 額度已用完（{}/{} 美分），略過回補。",
                    source.getQuotaUsed(), source.getQuotaLimit());
            return List.of();
        }

        List<GoogleTrendsClient.DailyInterest> series =
                client.fetchInterestOverTime(keyword.getKeyword(), timeframe);

        List<LocalDate> touchedDates = new ArrayList<>();
        for (var point : series) {
            if (point.date().isBefore(from) || point.date().isAfter(to)) {
                continue;
            }
            HeatReading reading = heatReadingRepository
                    .findByKeywordIdAndSourceIdAndReadingDate(keywordId, source.getId(), point.date())
                    .orElseGet(() -> HeatReading.builder()
                            .source(source).keyword(keyword).readingDate(point.date()).build());
            reading.setRawValue(BigDecimal.valueOf(point.value()));
            heatReadingRepository.save(reading);
            touchedDates.add(point.date());
        }

        return touchedDates.stream().distinct().sorted().toList();
    }
}
