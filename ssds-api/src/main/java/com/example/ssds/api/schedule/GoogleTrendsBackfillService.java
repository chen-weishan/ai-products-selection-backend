package com.example.ssds.api.schedule;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoogleTrendsBackfillService {

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
        TrendKeyword keyword = trendKeywordRepository.getReferenceById(keywordId);
        HeatSource source = heatSourceRepository.findBySourceCode(HeatSourceCode.GOOGLE_TRENDS)
                .orElseThrow();

        List<GoogleTrendsClient.DailyInterest> series =
                client.fetchInterestOverTime(keyword.getKeyword(), timeframe);

        List<LocalDate> touchedDates = new ArrayList<>();
        for (var point : series) {
            HeatReading reading = heatReadingRepository
                    .findByKeywordIdAndSourceIdAndReadingDate(keywordId, source.getId(), point.date())
                    .orElseGet(() -> HeatReading.builder()
                            .source(source).keyword(keyword).readingDate(point.date()).build());
            reading.setRawValue(BigDecimal.valueOf(point.value()));
            heatReadingRepository.save(reading);
            touchedDates.add(point.date());
        }

        touchedDates.stream().distinct().sorted().forEach(date -> {
            percentileDao.applyPercentiles(date);
            calibrationService.computeAndPersist(keywordId, date);
        });
    }
}