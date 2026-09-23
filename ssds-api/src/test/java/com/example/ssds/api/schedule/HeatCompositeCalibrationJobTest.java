package com.example.ssds.api.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.sourcing.SourcingTimeGapRecalculationJob;
import com.example.ssds.api.trend.TrendInterpretationJob;
import com.example.ssds.infra.dao.HeatReadingPercentileDao;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.infra.service.HeatCompositeCalibrationService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

class HeatCompositeCalibrationJobTest {

    @Test
    void agent5AndTimeGapAreEnabledByDefaultWhileStartupCatchUpStaysDisabled() throws Exception {
        Properties properties = new Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) {
            properties.load(input);
        }

        assertEquals(
                "${AI_TREND_SCHEDULE_ENABLED:true}",
                properties.getProperty("ai.trend.schedule-enabled"));
        assertEquals(
                "${AI_SOURCING_TIME_GAP_SCHEDULE_ENABLED:true}",
                properties.getProperty("ai.sourcing.time-gap-schedule-enabled"));
        assertEquals(
                "${HEAT_CATCH_UP_ENABLED:false}",
                properties.getProperty("ssds.calibration.heat-catch-up-enabled"));
    }

    @Test
    void onlyMainHeatJobOwnsTheDailyPipelineSchedule() throws Exception {
        Scheduled mainSchedule = HeatCompositeCalibrationJob.class
                .getDeclaredMethod("run")
                .getAnnotation(Scheduled.class);
        Scheduled instagramSchedule = InstagramHeatIngestJob.class
                .getDeclaredMethod("run")
                .getAnnotation(Scheduled.class);

        assertEquals("${ssds.calibration.heat-composite.cron:0 0 6 * * *}", mainSchedule.cron());
        assertEquals("${ssds.ingest.instagram.cron:0 30 3 * * MON}", instagramSchedule.cron());
        assertNull(SourcingTimeGapRecalculationJob.class
                .getDeclaredMethod("recalculateAfterDailyHeatComposition")
                .getAnnotation(Scheduled.class));
        assertNull(TrendInterpretationJob.class
                .getDeclaredMethod("enqueueSignificantKeywords", LocalDate.class)
                .getAnnotation(Scheduled.class));
    }

    @Test
    void runsPercentilesCompositionTimeGapAndAgentEnqueueInOrder() {
        HeatReadingPercentileDao percentileDao = mock(HeatReadingPercentileDao.class);
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeCalibrationService calibrationService = mock(HeatCompositeCalibrationService.class);
        SourcingTimeGapRecalculationJob timeGapJob = mock(SourcingTimeGapRecalculationJob.class);
        TrendInterpretationJob trendJob = mock(TrendInterpretationJob.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SourcingTimeGapRecalculationJob> timeGapProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TrendInterpretationJob> trendProvider = mock(ObjectProvider.class);
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        TrendKeyword keyword = TrendKeyword.builder().id(7L).keyword("每日鏈路").build();
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(keyword));
        when(calibrationService.computeAndPersist(7L, businessDate))
                .thenReturn(Optional.of(HeatCompositeDaily.builder().build()));
        when(timeGapProvider.getIfAvailable()).thenReturn(timeGapJob);
        when(trendProvider.getIfAvailable()).thenReturn(trendJob);
        HeatCompositeCalibrationJob job = new HeatCompositeCalibrationJob(
                percentileDao,
                keywordRepository,
                calibrationService,
                timeGapProvider,
                trendProvider);

        job.run(businessDate);

        InOrder order = inOrder(percentileDao, calibrationService, timeGapJob, trendJob);
        order.verify(percentileDao).applyPercentiles(businessDate);
        order.verify(calibrationService).computeAndPersist(7L, businessDate);
        order.verify(timeGapJob).recalculateAfterDailyHeatComposition();
        order.verify(trendJob).enqueueSignificantKeywords(businessDate);
    }

    @Test
    void oneKeywordFailureDoesNotBlockRemainingKeywordsOrDownstreamJobs() {
        HeatReadingPercentileDao percentileDao = mock(HeatReadingPercentileDao.class);
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeCalibrationService calibrationService = mock(HeatCompositeCalibrationService.class);
        SourcingTimeGapRecalculationJob timeGapJob = mock(SourcingTimeGapRecalculationJob.class);
        TrendInterpretationJob trendJob = mock(TrendInterpretationJob.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SourcingTimeGapRecalculationJob> timeGapProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TrendInterpretationJob> trendProvider = mock(ObjectProvider.class);
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        TrendKeyword failed = TrendKeyword.builder().id(7L).keyword("失敗關鍵字").build();
        TrendKeyword succeeded = TrendKeyword.builder().id(8L).keyword("成功關鍵字").build();
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(failed, succeeded));
        when(calibrationService.computeAndPersist(7L, businessDate))
                .thenThrow(new IllegalStateException("isolated failure"));
        when(calibrationService.computeAndPersist(8L, businessDate))
                .thenReturn(Optional.of(HeatCompositeDaily.builder().build()));
        when(timeGapProvider.getIfAvailable()).thenReturn(timeGapJob);
        when(trendProvider.getIfAvailable()).thenReturn(trendJob);
        HeatCompositeCalibrationJob job = new HeatCompositeCalibrationJob(
                percentileDao,
                keywordRepository,
                calibrationService,
                timeGapProvider,
                trendProvider);

        job.run(businessDate);

        verify(calibrationService).computeAndPersist(8L, businessDate);
        verify(timeGapJob).recalculateAfterDailyHeatComposition();
        verify(trendJob).enqueueSignificantKeywords(businessDate);
    }

    @Test
    void catchUpLimitsAgentEnqueueToPreviouslyMissingKeywords() {
        HeatReadingPercentileDao percentileDao = mock(HeatReadingPercentileDao.class);
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeCalibrationService calibrationService = mock(HeatCompositeCalibrationService.class);
        TrendInterpretationJob trendJob = mock(TrendInterpretationJob.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SourcingTimeGapRecalculationJob> timeGapProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TrendInterpretationJob> trendProvider = mock(ObjectProvider.class);
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        TrendKeyword existing = TrendKeyword.builder().id(7L).keyword("既有關鍵字").build();
        TrendKeyword added = TrendKeyword.builder().id(8L).keyword("新增關鍵字").build();
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(existing, added));
        when(trendProvider.getIfAvailable()).thenReturn(trendJob);
        HeatCompositeCalibrationJob job = new HeatCompositeCalibrationJob(
                percentileDao,
                keywordRepository,
                calibrationService,
                timeGapProvider,
                trendProvider);

        job.runCatchUp(businessDate, List.of(8L));

        verify(calibrationService).computeAndPersist(7L, businessDate);
        verify(calibrationService).computeAndPersist(8L, businessDate);
        verify(trendJob).enqueueSignificantKeywords(businessDate, List.of(8L));
        verify(trendJob, never()).enqueueSignificantKeywords(businessDate);
    }
}
