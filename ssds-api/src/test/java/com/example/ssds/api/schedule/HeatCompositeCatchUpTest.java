package com.example.ssds.api.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.support.CronExpression;

@ExtendWith(OutputCaptureExtension.class)
class HeatCompositeCatchUpTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 21);

    @Test
    void catchUpIsDisabledWhenPropertyIsMissing() {
        ConditionalOnProperty condition = HeatCompositeCatchUp.class
                .getAnnotation(ConditionalOnProperty.class);

        assertEquals("ssds.calibration.heat-catch-up-enabled", condition.name()[0]);
        assertEquals("true", condition.havingValue());
        assertFalse(condition.matchIfMissing());
    }

    @Test
    void runsAfterScheduledTimeWhenTodayIsIncomplete() {
        HeatCompositeCalibrationJob job = mock(HeatCompositeCalibrationJob.class);
        ThreadsHeatIngestJob threadsJob = mock(ThreadsHeatIngestJob.class);
        GoogleTrendsHeatIngestJob trendsJob = mock(GoogleTrendsHeatIngestJob.class);
        InstagramHeatIngestJob instagramJob = mock(InstagramHeatIngestJob.class);
        TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository composites = mock(HeatCompositeDailyRepository.class);
        HeatReadingRepository readings = mock(HeatReadingRepository.class);
        when(keywords.countByEnabledTrue()).thenReturn(3L);
        when(composites.findEnabledKeywordIdsMissingStatDate(BUSINESS_DATE))
                .thenReturn(java.util.List.of(7L));
        HeatCompositeCatchUp catchUp = catchUpAt(
                "2026-09-20T23:01:00Z",
                job,
                threadsJob,
                trendsJob,
                instagramJob,
                keywords,
                composites,
                readings);

        catchUp.catchUp(ZonedDateTime.now(fixedClock("2026-09-20T23:01:00Z")));

        InOrder order = inOrder(threadsJob, trendsJob, instagramJob, job);
        order.verify(instagramJob).run();
        order.verify(threadsJob).runForKeywordIds(java.util.List.of(7L), BUSINESS_DATE);
        order.verify(trendsJob).runForKeywordIds(java.util.List.of(7L), BUSINESS_DATE);
        order.verify(job).runCatchUp(BUSINESS_DATE, java.util.List.of(7L));
    }

    @Test
    void skipsBeforeWeeklyScheduleAndWhenWeekAndDayAreComplete(CapturedOutput output) {
        HeatCompositeCalibrationJob beforeJob = mock(HeatCompositeCalibrationJob.class);
        ThreadsHeatIngestJob beforeThreads = mock(ThreadsHeatIngestJob.class);
        GoogleTrendsHeatIngestJob beforeTrends = mock(GoogleTrendsHeatIngestJob.class);
        InstagramHeatIngestJob beforeInstagram = mock(InstagramHeatIngestJob.class);
        TrendKeywordRepository beforeKeywords = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository beforeComposites = mock(HeatCompositeDailyRepository.class);
        HeatReadingRepository beforeReadings = mock(HeatReadingRepository.class);
        HeatCompositeCatchUp before = catchUpAt(
                "2026-09-20T18:00:00Z",
                beforeJob,
                beforeThreads,
                beforeTrends,
                beforeInstagram,
                beforeKeywords,
                beforeComposites,
                beforeReadings);

        before.catchUp(ZonedDateTime.now(fixedClock("2026-09-20T18:00:00Z")));

        verify(beforeJob, never()).run(BUSINESS_DATE);
        verify(beforeInstagram, never()).run();

        HeatCompositeCalibrationJob completeJob = mock(HeatCompositeCalibrationJob.class);
        ThreadsHeatIngestJob completeThreads = mock(ThreadsHeatIngestJob.class);
        GoogleTrendsHeatIngestJob completeTrends = mock(GoogleTrendsHeatIngestJob.class);
        InstagramHeatIngestJob completeInstagram = mock(InstagramHeatIngestJob.class);
        TrendKeywordRepository completeKeywords = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository completeComposites = mock(HeatCompositeDailyRepository.class);
        HeatReadingRepository completeReadings = mock(HeatReadingRepository.class);
        when(completeKeywords.countByEnabledTrue()).thenReturn(3L);
        when(completeComposites.findEnabledKeywordIdsMissingStatDate(BUSINESS_DATE))
                .thenReturn(java.util.List.of());
        when(completeReadings.existsBySourceSourceCodeAndReadingDateBetween(
                HeatSourceCode.INSTAGRAM, BUSINESS_DATE, BUSINESS_DATE.plusDays(6)))
                .thenReturn(true);
        HeatCompositeCatchUp complete = catchUpAt(
                "2026-09-20T23:01:00Z",
                completeJob,
                completeThreads,
                completeTrends,
                completeInstagram,
                completeKeywords,
                completeComposites,
                completeReadings);

        complete.catchUp(ZonedDateTime.now(fixedClock("2026-09-20T23:01:00Z")));

        verify(completeJob, never()).run(BUSINESS_DATE);
        verify(completeInstagram, never()).run();
        assertTrue(output.getOut().contains(
                "本週 Instagram 熱度資料已存在，不需補跑：weekStart=2026-09-21"));
    }

    @Test
    void catchesUpMissingInstagramOnTuesdayWithoutRepeatingDailySources() {
        LocalDate tuesday = LocalDate.of(2026, 9, 22);
        HeatCompositeCalibrationJob job = mock(HeatCompositeCalibrationJob.class);
        ThreadsHeatIngestJob threadsJob = mock(ThreadsHeatIngestJob.class);
        GoogleTrendsHeatIngestJob trendsJob = mock(GoogleTrendsHeatIngestJob.class);
        InstagramHeatIngestJob instagramJob = mock(InstagramHeatIngestJob.class);
        TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository composites = mock(HeatCompositeDailyRepository.class);
        HeatReadingRepository readings = mock(HeatReadingRepository.class);
        when(keywords.countByEnabledTrue()).thenReturn(3L);
        when(composites.findEnabledKeywordIdsMissingStatDate(tuesday))
                .thenReturn(java.util.List.of());
        HeatCompositeCatchUp catchUp = catchUpAt(
                "2026-09-21T23:01:00Z",
                job,
                threadsJob,
                trendsJob,
                instagramJob,
                keywords,
                composites,
                readings);

        catchUp.catchUp(ZonedDateTime.now(fixedClock("2026-09-21T23:01:00Z")));

        InOrder order = inOrder(instagramJob, job);
        order.verify(instagramJob).run();
        order.verify(job).runCatchUp(tuesday, java.util.List.of());
        verify(threadsJob, never()).run();
        verify(trendsJob, never()).run();
    }

    @Test
    void skipsDisabledSourceJobsEvenWhenDailyCatchUpIsDue() {
        HeatCompositeCalibrationJob job = mock(HeatCompositeCalibrationJob.class);
        ObjectProvider<ThreadsHeatIngestJob> threadsProvider = provider(null);
        ObjectProvider<GoogleTrendsHeatIngestJob> trendsProvider = provider(null);
        ObjectProvider<InstagramHeatIngestJob> instagramProvider = provider(null);
        TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository composites = mock(HeatCompositeDailyRepository.class);
        HeatReadingRepository readings = mock(HeatReadingRepository.class);
        when(keywords.countByEnabledTrue()).thenReturn(3L);
        when(composites.findEnabledKeywordIdsMissingStatDate(BUSINESS_DATE))
                .thenReturn(java.util.List.of(7L));
        HeatCompositeCatchUp catchUp = new HeatCompositeCatchUp(
                job,
                threadsProvider,
                trendsProvider,
                instagramProvider,
                keywords,
                composites,
                readings,
                CronExpression.parse("0 0 6 * * *"),
                CronExpression.parse("0 30 3 * * MON"),
                fixedClock("2026-09-20T23:01:00Z"));

        catchUp.catchUp(ZonedDateTime.now(fixedClock("2026-09-20T23:01:00Z")));

        verify(job).runCatchUp(BUSINESS_DATE, java.util.List.of(7L));
        verify(threadsProvider).getIfAvailable();
        verify(trendsProvider).getIfAvailable();
        verify(instagramProvider).getIfAvailable();
        verifyNoInteractions(readings);
    }

    private static HeatCompositeCatchUp catchUpAt(
            String instant,
            HeatCompositeCalibrationJob job,
            ThreadsHeatIngestJob threadsJob,
            GoogleTrendsHeatIngestJob trendsJob,
            InstagramHeatIngestJob instagramJob,
            TrendKeywordRepository keywords,
            HeatCompositeDailyRepository composites,
            HeatReadingRepository readings) {
        return new HeatCompositeCatchUp(
                job,
                provider(threadsJob),
                provider(trendsJob),
                provider(instagramJob),
                keywords,
                composites,
                readings,
                CronExpression.parse("0 0 6 * * *"),
                CronExpression.parse("0 30 3 * * MON"),
                fixedClock(instant));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), TAIPEI);
    }
}
