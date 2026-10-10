package com.example.ssds.api.sourcing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.example.ssds.api.schedule.GoogleTrendsBackfillService;
import com.example.ssds.api.schedule.InstagramHeatIngestJob;
import com.example.ssds.api.schedule.ThreadsBackfillService;
import com.example.ssds.api.trend.TrendInterpretationJob;
import com.example.ssds.infra.dao.HeatReadingPercentileDao;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.service.HeatCompositeCalibrationService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SourcingKeywordHeatBackfillServiceTest {

    @Test
    void backfillsExactlyTodayAndPreviousSixDaysThenChecksAgentFive() {
        ThreadsBackfillService threads = mock(ThreadsBackfillService.class);
        GoogleTrendsBackfillService google = mock(GoogleTrendsBackfillService.class);
        InstagramHeatIngestJob instagram = mock(InstagramHeatIngestJob.class);
        HeatReadingPercentileDao percentiles = mock(HeatReadingPercentileDao.class);
        HeatCompositeCalibrationService calibration = mock(HeatCompositeCalibrationService.class);
        TrendInterpretationJob agentFive = mock(TrendInterpretationJob.class);
        SourcingTimeGapRecalculationService timeGap = mock(SourcingTimeGapRecalculationService.class);
        SourcingKeywordHeatBackfillService service = new SourcingKeywordHeatBackfillService(
                threads, google, instagram, percentiles, calibration, agentFive, timeGap);
        LocalDate today = LocalDate.of(2026, 10, 10);
        when(calibration.computeAndPersistWithRawSevenDayAnchor(eq(41L), any(LocalDate.class)))
                .thenReturn(Optional.of(mock(HeatCompositeDaily.class)));

        service.backfill(41L, today);

        verify(threads).backfillRangeForKeyword(41L, LocalDate.of(2026, 9, 27), today);
        verify(google).backfillKeywordReadings(
                41L, "now 14-d", LocalDate.of(2026, 9, 27), today);
        verify(instagram).runMissingForWeek(LocalDate.of(2026, 9, 27));
        verify(instagram).runMissingForWeek(LocalDate.of(2026, 9, 28));
        verify(instagram).runMissingForWeek(LocalDate.of(2026, 10, 5));
        verifyNoMoreInteractions(instagram);
        ArgumentCaptor<LocalDate> dates = ArgumentCaptor.forClass(LocalDate.class);
        verify(calibration, times(7))
                .computeAndPersistWithRawSevenDayAnchor(eq(41L), dates.capture());
        assertEquals(List.of(
                LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7),
                LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9), today),
                dates.getAllValues());
        var order = inOrder(timeGap, agentFive);
        order.verify(timeGap).recalculateAffectedByKeyword(41L);
        order.verify(agentFive).enqueueSignificantKeywordsForDates(
                dates.getAllValues(), List.of(41L));
    }

    @Test
    void oneFailedSourceDoesNotPreventSevenDayComposition() {
        ThreadsBackfillService threads = mock(ThreadsBackfillService.class);
        GoogleTrendsBackfillService google = mock(GoogleTrendsBackfillService.class);
        InstagramHeatIngestJob instagram = mock(InstagramHeatIngestJob.class);
        HeatReadingPercentileDao percentiles = mock(HeatReadingPercentileDao.class);
        HeatCompositeCalibrationService calibration = mock(HeatCompositeCalibrationService.class);
        TrendInterpretationJob agentFive = mock(TrendInterpretationJob.class);
        SourcingTimeGapRecalculationService timeGap = mock(SourcingTimeGapRecalculationService.class);
        SourcingKeywordHeatBackfillService service = new SourcingKeywordHeatBackfillService(
                threads, google, instagram, percentiles, calibration, agentFive, timeGap);
        doThrow(new IllegalStateException("source unavailable"))
                .when(instagram).runMissingForWeek(any(LocalDate.class));

        service.backfill(41L, LocalDate.of(2026, 10, 10));

        verify(calibration, times(7))
                .computeAndPersistWithRawSevenDayAnchor(eq(41L), any(LocalDate.class));
        verify(timeGap).recalculateAffectedByKeyword(41L);
        verify(agentFive).enqueueSignificantKeywordsForDates(List.of(), List.of(41L));
    }
}
