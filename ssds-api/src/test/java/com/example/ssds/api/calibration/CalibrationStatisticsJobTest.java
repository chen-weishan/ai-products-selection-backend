package com.example.ssds.api.calibration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ssds.api.aitask.service.AiTaskService;
import com.example.ssds.api.calibration.dto.CalibrationReportResponse;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.core.domain.AiTaskType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalibrationStatisticsJobTest {

    private final CalibrationReportService reportService = mock(CalibrationReportService.class);
    private final AiTaskService aiTaskService = mock(AiTaskService.class);

    private CalibrationStatisticsJob jobAt(String instant, boolean interpretEnabled) {
        return new CalibrationStatisticsJob(reportService, aiTaskService, interpretEnabled,
                Clock.fixed(Instant.parse(instant), CalibrationQuarter.ZONE));
    }

    private void reportGenerated(String quarter, long id) {
        CalibrationReportResponse report = mock(CalibrationReportResponse.class);
        when(report.id()).thenReturn(id);
        when(reportService.generate(quarter)).thenReturn(report);
    }

    @Test
    void januaryGeneratesPreviousYearsFourthQuarterThenCreatesInterpretationTask() {
        reportGenerated("2025Q4", 7L);

        jobAt("2026-01-01T00:00:00Z", true).run(); // 台北 2026-01-01（週四）08:00

        verify(reportService).generate("2025Q4");
        verify(aiTaskService).create(argThat(request ->
                request.taskType() == AiTaskType.WEIGHT_CALIBRATION
                        && request.calibrationReportIds().equals(List.of(7L))
                        && !request.forceRefresh()));
    }

    @Test
    void weekdayNineOClockRunIsSkipped() {
        jobAt("2026-01-01T01:00:00Z", true).run(); // 週四 09:00：08:00 已跑過

        verifyNoInteractions(reportService, aiTaskService);
    }

    @Test
    void mondayQuarterStartWaitsUntilNineOClock() {
        jobAt("2029-01-01T00:00:00Z", true).run(); // 2029-01-01 為週一，08:00 不跑

        verifyNoInteractions(reportService, aiTaskService);
    }

    @Test
    void mondayQuarterStartRunsAtNineOClock() {
        reportGenerated("2028Q4", 8L);

        jobAt("2029-01-01T01:00:00Z", true).run();

        verify(aiTaskService).create(argThat(request -> request.calibrationReportIds().equals(List.of(8L))));
    }

    @Test
    void skippedReportDoesNotCreateInterpretationTask() {
        when(reportService.generate("2026Q3"))
                .thenThrow(new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "已審核"));

        jobAt("2026-10-01T00:00:00Z", true).run();

        verify(aiTaskService, never()).create(any());
    }

    @Test
    void interpretationDisabledOnlyGeneratesReport() {
        reportGenerated("2026Q3", 9L);

        jobAt("2026-10-01T00:00:00Z", false).run();

        verify(reportService).generate("2026Q3");
        verifyNoInteractions(aiTaskService);
    }

    @Test
    void interpretationFailureDoesNotPropagate() {
        reportGenerated("2026Q3", 9L);
        when(aiTaskService.create(any())).thenThrow(new IllegalStateException("budget"));

        jobAt("2026-10-01T00:00:00Z", true).run();

        verify(aiTaskService).create(any());
    }
}
