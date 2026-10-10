package com.example.ssds.api.sourcing;

import com.example.ssds.api.schedule.GoogleTrendsBackfillService;
import com.example.ssds.api.schedule.InstagramHeatIngestJob;
import com.example.ssds.api.schedule.ThreadsBackfillService;
import com.example.ssds.api.trend.TrendInterpretationJob;
import com.example.ssds.infra.dao.HeatReadingPercentileDao;
import com.example.ssds.infra.service.HeatCompositeCalibrationService;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 為 S-17 新增的陌生關鍵字回補含當日在內的七日熱度鏈。 */
@Service
public class SourcingKeywordHeatBackfillService {
    static final int BACKFILL_DAYS = 7;
    static final String GOOGLE_TRENDS_TIMEFRAME = "now 14-d";

    private static final Logger log = LoggerFactory.getLogger(SourcingKeywordHeatBackfillService.class);

    private final ThreadsBackfillService threadsBackfillService;
    private final GoogleTrendsBackfillService googleTrendsBackfillService;
    private final InstagramHeatIngestJob instagramHeatIngestJob;
    private final HeatReadingPercentileDao percentileDao;
    private final HeatCompositeCalibrationService calibrationService;
    private final TrendInterpretationJob trendInterpretationJob;
    private final SourcingTimeGapRecalculationService timeGapRecalculationService;

    public SourcingKeywordHeatBackfillService(
            ThreadsBackfillService threadsBackfillService,
            GoogleTrendsBackfillService googleTrendsBackfillService,
            InstagramHeatIngestJob instagramHeatIngestJob,
            HeatReadingPercentileDao percentileDao,
            HeatCompositeCalibrationService calibrationService,
            TrendInterpretationJob trendInterpretationJob,
            SourcingTimeGapRecalculationService timeGapRecalculationService) {
        this.threadsBackfillService = threadsBackfillService;
        this.googleTrendsBackfillService = googleTrendsBackfillService;
        this.instagramHeatIngestJob = instagramHeatIngestJob;
        this.percentileDao = percentileDao;
        this.calibrationService = calibrationService;
        this.trendInterpretationJob = trendInterpretationJob;
        this.timeGapRecalculationService = timeGapRecalculationService;
    }

    public void backfill(Long keywordId, LocalDate businessDate) {
        LocalDate startDate = businessDate.minusDays(BACKFILL_DAYS - 1L);
        LocalDate sourceStartDate = startDate.minusDays(7);
        backfillInstagramWeeks(keywordId, sourceStartDate, businessDate);
        collectSafely("Threads", keywordId, () ->
                threadsBackfillService.backfillRangeForKeyword(
                        keywordId, sourceStartDate, businessDate));
        collectSafely("Google Trends", keywordId, () ->
                googleTrendsBackfillService.backfillKeywordReadings(
                        keywordId, GOOGLE_TRENDS_TIMEFRAME, sourceStartDate, businessDate));

        for (LocalDate date = sourceStartDate;
                !date.isAfter(businessDate);
                date = date.plusDays(1)) {
            percentileDao.applyPercentiles(date);
            percentileDao.applyInstagramWeeklyPercentiles(date);
        }

        List<LocalDate> composedDates = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(businessDate); date = date.plusDays(1)) {
            try {
                if (calibrationService
                        .computeAndPersistWithRawSevenDayAnchor(keywordId, date)
                        .isPresent()) {
                    composedDates.add(date);
                }
            } catch (RuntimeException exception) {
                log.warn("S-17 關鍵字七日熱度合成失敗：keywordId={} date={}",
                        keywordId, date, exception);
            }
        }
        timeGapRecalculationService.recalculateAffectedByKeyword(keywordId);
        trendInterpretationJob.enqueueSignificantKeywordsForDates(
                composedDates, List.of(keywordId));
        log.info("S-17 關鍵字七日熱度回補完成：keywordId={} composedDates={}",
                keywordId, composedDates.size());
    }

    private void backfillInstagramWeeks(
            Long keywordId, LocalDate startDate, LocalDate businessDate) {
        Map<LocalDate, LocalDate> firstDateByWeek = new LinkedHashMap<>();
        for (LocalDate date = startDate; !date.isAfter(businessDate); date = date.plusDays(1)) {
            LocalDate weekStart = date.with(
                    TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            firstDateByWeek.putIfAbsent(weekStart, date);
        }
        for (LocalDate firstDateInRange : firstDateByWeek.values()) {
            collectSafely("Instagram", keywordId,
                    () -> instagramHeatIngestJob.runMissingForWeek(firstDateInRange));
        }
    }

    private void collectSafely(String source, Long keywordId, Runnable collection) {
        try {
            collection.run();
        } catch (RuntimeException exception) {
            // 單一來源失敗時仍以其他可用來源完成合成，符合既有降級語意。
            log.warn("S-17 關鍵字歷史熱度採集失敗：source={} keywordId={}",
                    source, keywordId, exception);
        }
    }
}
