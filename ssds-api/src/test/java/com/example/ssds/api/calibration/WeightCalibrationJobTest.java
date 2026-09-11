package com.example.ssds.api.calibration;

import static org.mockito.Mockito.*;

import com.example.ssds.api.aitask.AiTaskService;
import com.example.ssds.api.aitask.dto.CreateAiTaskRequest;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.infra.entity.CalibrationReport;
import com.example.ssds.infra.repository.CalibrationReportRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WeightCalibrationJobTest {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Test
    void januaryRunReadsPreviousYearsFourthQuarter() {
        CalibrationReportRepository reports = mock(CalibrationReportRepository.class);
        AiTaskService taskService = mock(AiTaskService.class);
        CalibrationReport report = CalibrationReport.builder().id(7L).quarter("2025Q4").build();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), TAIPEI);
        when(reports.findByQuarter("2025Q4")).thenReturn(Optional.of(report));

        new WeightCalibrationJob(reports, taskService, clock).run();

        verify(reports).findByQuarter("2025Q4");
        verify(taskService).create(argThat(request ->
                request.taskType() == AiTaskType.WEIGHT_CALIBRATION
                        && request.calibrationReportIds().equals(java.util.List.of(7L))
                        && !request.forceRefresh()));
    }

    @Test
    void mondayQuarterStartWaitsUntilNineOClock() {
        CalibrationReportRepository reports = mock(CalibrationReportRepository.class);
        AiTaskService taskService = mock(AiTaskService.class);
        Clock eightOClock = Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), TAIPEI);

        new WeightCalibrationJob(reports, taskService, eightOClock).run();

        verifyNoInteractions(reports, taskService);
    }

    @Test
    void mondayQuarterStartRunsAtNineOClock() {
        CalibrationReportRepository reports = mock(CalibrationReportRepository.class);
        AiTaskService taskService = mock(AiTaskService.class);
        CalibrationReport report = CalibrationReport.builder().id(8L).quarter("2028Q4").build();
        Clock nineOClock = Clock.fixed(Instant.parse("2029-01-01T01:00:00Z"), TAIPEI);
        when(reports.findByQuarter("2028Q4")).thenReturn(Optional.of(report));

        new WeightCalibrationJob(reports, taskService, nineOClock).run();

        verify(taskService).create(argThat(request ->
                request.taskType() == AiTaskType.WEIGHT_CALIBRATION
                        && request.calibrationReportIds().equals(java.util.List.of(8L))));
    }
}
