package com.example.ssds.api.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ssds.api.sourcing.SourcingTimeGapRecalculationJob;
import com.example.ssds.api.trend.TrendInterpretationJob;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.infra.dao.HeatReadingPercentileDao;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.infra.service.HeatCompositeCalibrationService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
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
    void enqueuesAgentBeforeSkippingItsDrivingKeywordInBaselineTimeGapPass() {
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
        when(trendJob.enqueueSignificantKeywords(businessDate)).thenReturn(Set.of(7L));
        HeatCompositeCalibrationJob job = new HeatCompositeCalibrationJob(
                percentileDao,
                keywordRepository,
                mock(HeatCompositeDailyRepository.class),
                mock(HeatReadingRepository.class),
                calibrationService,
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                timeGapProvider,
                trendProvider);

        job.run(businessDate);

        InOrder order = inOrder(percentileDao, calibrationService, timeGapJob, trendJob);
        order.verify(percentileDao).applyPercentiles(businessDate);
        order.verify(percentileDao).applyInstagramWeeklyPercentiles(businessDate);
        order.verify(calibrationService).computeAndPersist(7L, businessDate);
        order.verify(trendJob).enqueueSignificantKeywords(businessDate);
        order.verify(timeGapJob).recalculateAfterDailyHeatComposition(Set.of(7L));
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
        when(trendJob.enqueueSignificantKeywords(businessDate)).thenReturn(Set.of(8L));
        HeatCompositeCalibrationJob job = new HeatCompositeCalibrationJob(
                percentileDao,
                keywordRepository,
                mock(HeatCompositeDailyRepository.class),
                mock(HeatReadingRepository.class),
                calibrationService,
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                timeGapProvider,
                trendProvider);

        job.run(businessDate);

        verify(calibrationService).computeAndPersist(8L, businessDate);
        verify(trendJob).enqueueSignificantKeywords(businessDate);
        verify(timeGapJob).recalculateAfterDailyHeatComposition(Set.of(8L));
    }

    @Test
    void generalCatchUpLimitsCompositionAndAgentEnqueueToMissingKeywords() {
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
                mock(HeatCompositeDailyRepository.class),
                mock(HeatReadingRepository.class),
                calibrationService,
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                timeGapProvider,
                trendProvider);

        job.runCatchUp(businessDate, List.of(8L));

        verify(calibrationService, never()).computeAndPersist(7L, businessDate);
        verify(calibrationService).computeAndPersist(8L, businessDate);
        verify(trendJob).enqueueSignificantKeywords(businessDate, List.of(8L));
        verify(trendJob, never()).enqueueSignificantKeywords(businessDate);
    }

    @Test
    void instagramCatchUpRecomposesAndEvaluatesAllEnabledKeywords() {
        HeatReadingPercentileDao percentileDao = mock(HeatReadingPercentileDao.class);
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeCalibrationService calibrationService = mock(HeatCompositeCalibrationService.class);
        TrendInterpretationJob trendJob = mock(TrendInterpretationJob.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SourcingTimeGapRecalculationJob> timeGapProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TrendInterpretationJob> trendProvider = mock(ObjectProvider.class);
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        TrendKeyword first = TrendKeyword.builder().id(7L).keyword("第一個關鍵字").build();
        TrendKeyword second = TrendKeyword.builder().id(8L).keyword("第二個關鍵字").build();
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(first, second));
        when(trendProvider.getIfAvailable()).thenReturn(trendJob);
        HeatCompositeCalibrationJob job = new HeatCompositeCalibrationJob(
                percentileDao,
                keywordRepository,
                mock(HeatCompositeDailyRepository.class),
                mock(HeatReadingRepository.class),
                calibrationService,
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                timeGapProvider,
                trendProvider);

        job.runCatchUpAll(businessDate);

        verify(calibrationService).computeAndPersist(7L, businessDate);
        verify(calibrationService).computeAndPersist(8L, businessDate);
        verify(trendJob).enqueueSignificantKeywords(businessDate);
        verify(trendJob, never()).enqueueSignificantKeywords(businessDate, List.of(7L, 8L));
    }

    @Test
    void scheduledRunRepairsMissingReadingsBeforePercentilesAndComposition() {
        HeatReadingPercentileDao percentileDao = mock(HeatReadingPercentileDao.class);
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        HeatReadingRepository heatReadingRepository = mock(HeatReadingRepository.class);
        HeatCompositeCalibrationService calibrationService = mock(HeatCompositeCalibrationService.class);
        ThreadsHeatIngestJob threadsJob = mock(ThreadsHeatIngestJob.class);
        GoogleTrendsHeatIngestJob googleTrendsJob = mock(GoogleTrendsHeatIngestJob.class);
        InstagramHeatIngestJob instagramJob = mock(InstagramHeatIngestJob.class);
        ManualHeatReadingJob manualJob = mock(ManualHeatReadingJob.class);
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        TrendKeyword keyword = TrendKeyword.builder().id(7L).keyword("合成前補採").build();
        when(compositeRepository.findEnabledKeywordIdsMissingStatDate(businessDate))
                .thenReturn(List.of(7L));
        when(heatReadingRepository.existsBySourceSourceCodeAndReadingDateBetween(
                HeatSourceCode.INSTAGRAM,
                LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 9, 27)))
                .thenReturn(false);
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(keyword));
        HeatCompositeCalibrationJob job = new HeatCompositeCalibrationJob(
                percentileDao,
                keywordRepository,
                compositeRepository,
                heatReadingRepository,
                calibrationService,
                provider(threadsJob),
                provider(googleTrendsJob),
                provider(instagramJob),
                provider(manualJob),
                provider(null),
                provider(null));

        job.runScheduled(businessDate);

        InOrder order = inOrder(
                threadsJob, googleTrendsJob, instagramJob, manualJob, percentileDao, calibrationService);
        order.verify(threadsJob).runForKeywordIds(List.of(7L), businessDate);
        order.verify(googleTrendsJob).runForKeywordIds(List.of(7L), businessDate);
        order.verify(instagramJob).runMissingForWeek(businessDate);
        order.verify(manualJob).reconcile(org.mockito.ArgumentMatchers.eq(businessDate), org.mockito.ArgumentMatchers.any());
        order.verify(percentileDao).applyPercentiles(businessDate);
        order.verify(percentileDao).applyInstagramWeeklyPercentiles(businessDate);
        order.verify(calibrationService).computeAndPersist(7L, businessDate);
    }

    @Test
    void scheduledRerunOnlyReconcilesExistingInstagramWithoutRepeatingCollection() {
        HeatReadingPercentileDao percentileDao = mock(HeatReadingPercentileDao.class);
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        HeatReadingRepository heatReadingRepository = mock(HeatReadingRepository.class);
        ThreadsHeatIngestJob threadsJob = mock(ThreadsHeatIngestJob.class);
        GoogleTrendsHeatIngestJob googleTrendsJob = mock(GoogleTrendsHeatIngestJob.class);
        InstagramHeatIngestJob instagramJob = mock(InstagramHeatIngestJob.class);
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        when(compositeRepository.findEnabledKeywordIdsMissingStatDate(businessDate))
                .thenReturn(List.of());
        when(heatReadingRepository.existsBySourceSourceCodeAndReadingDateBetween(
                HeatSourceCode.INSTAGRAM,
                LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 9, 27)))
                .thenReturn(true);
        HeatCompositeCalibrationJob job = new HeatCompositeCalibrationJob(
                percentileDao,
                keywordRepository,
                compositeRepository,
                heatReadingRepository,
                mock(HeatCompositeCalibrationService.class),
                provider(threadsJob),
                provider(googleTrendsJob),
                provider(instagramJob),
                provider(null),
                provider(null),
                provider(null));

        job.runScheduled(businessDate);

        verifyNoInteractions(threadsJob, googleTrendsJob);
        verify(instagramJob).runMissingForWeek(businessDate);
        verify(instagramJob).restoreAvailabilityFromCurrentWeek(businessDate);
        verify(percentileDao).applyPercentiles(businessDate);
        verify(percentileDao).applyInstagramWeeklyPercentiles(businessDate);
    }

    @Test
    void scheduledRunStillFillsMissingInstagramCategoriesWhenWeekHasPartialData() {
        HeatReadingPercentileDao percentileDao = mock(HeatReadingPercentileDao.class);
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        HeatReadingRepository heatReadingRepository = mock(HeatReadingRepository.class);
        InstagramHeatIngestJob instagramJob = mock(InstagramHeatIngestJob.class);
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        when(compositeRepository.findEnabledKeywordIdsMissingStatDate(businessDate))
                .thenReturn(List.of());
        when(heatReadingRepository.existsBySourceSourceCodeAndReadingDateBetween(
                HeatSourceCode.INSTAGRAM,
                LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 9, 27)))
                .thenReturn(true);
        when(instagramJob.runMissingForWeek(businessDate)).thenReturn(true);
        HeatCompositeCalibrationJob job = new HeatCompositeCalibrationJob(
                percentileDao,
                keywordRepository,
                compositeRepository,
                heatReadingRepository,
                mock(HeatCompositeCalibrationService.class),
                provider(null),
                provider(null),
                provider(instagramJob),
                provider(null),
                provider(null),
                provider(null));

        job.runScheduled(businessDate);

        verify(instagramJob).runMissingForWeek(businessDate);
        verify(instagramJob, never()).restoreAvailabilityFromCurrentWeek(businessDate);
        verify(percentileDao).applyPercentiles(businessDate);
        verify(percentileDao).applyInstagramWeeklyPercentiles(businessDate);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
