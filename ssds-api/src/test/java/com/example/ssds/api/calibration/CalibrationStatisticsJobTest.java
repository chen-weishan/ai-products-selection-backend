package com.example.ssds.api.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ssds.api.calibration.dto.CalibrationReportResponse;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CalibrationStatisticsJobTest {

    private final CalibrationReportService reportService = mock(CalibrationReportService.class);
    private final CalibrationInterpretations interpretations = mock(CalibrationInterpretations.class);

    private CalibrationStatisticsJob jobAt(String instant, boolean interpretEnabled) {
        return new CalibrationStatisticsJob(reportService, interpretations, interpretEnabled,
                Clock.fixed(Instant.parse(instant), CalibrationQuarter.ZONE));
    }

    private void reportGenerated(String quarter, long id) {
        CalibrationReportResponse report = mock(CalibrationReportResponse.class);
        when(report.id()).thenReturn(id);
        when(reportService.generate(quarter)).thenReturn(report);
    }

    @Test
    void januaryGeneratesPreviousYearsFourthQuarterThenRequestsInterpretation() {
        reportGenerated("2025Q4", 7L);

        jobAt("2026-01-01T00:00:00Z", true).run(); // 台北 2026-01-01（週四）08:00

        verify(reportService).generate("2025Q4");
        verify(interpretations).request(7L, "2025Q4");
    }

    @Test
    void weekdayNineOClockRunIsSkipped() {
        jobAt("2026-01-01T01:00:00Z", true).run(); // 週四 09:00：08:00 已跑過

        verifyNoInteractions(reportService, interpretations);
    }

    @Test
    void mondayQuarterStartWaitsUntilNineOClock() {
        jobAt("2029-01-01T00:00:00Z", true).run(); // 2029-01-01 為週一，08:00 不跑

        verifyNoInteractions(reportService, interpretations);
    }

    @Test
    void mondayQuarterStartRunsAtNineOClock() {
        reportGenerated("2028Q4", 8L);

        jobAt("2029-01-01T01:00:00Z", true).run();

        verify(interpretations).request(8L, "2028Q4");
    }

    @Test
    void skippedReportDoesNotRequestInterpretation() {
        when(reportService.generate("2026Q3"))
                .thenThrow(new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "已審核"));

        jobAt("2026-10-01T00:00:00Z", true).run();

        verify(interpretations, never()).request(anyLong(), any());
    }

    @Test
    void interpretationDisabledOnlyGeneratesReport() {
        reportGenerated("2026Q3", 9L);

        jobAt("2026-10-01T00:00:00Z", false).run();

        verify(reportService).generate("2026Q3");
        verifyNoInteractions(interpretations);
    }

    @Test
    void cronWithoutNineOClockIsDetected() {
        assertThat(CalibrationStatisticsJob.coversMondayNineOClock(CalibrationStatisticsJob.DEFAULT_CRON)).isTrue();
        // 規格書附錄 B 的預設值：首日為週一的季度會漏產
        assertThat(CalibrationStatisticsJob.coversMondayNineOClock("0 0 8 1 1,4,7,10 *")).isFalse();
        assertThat(CalibrationStatisticsJob.coversMondayNineOClock("0 */5 * * * *")).isTrue();
        assertThat(CalibrationStatisticsJob.coversMondayNineOClock("-")).isTrue();
    }
}
