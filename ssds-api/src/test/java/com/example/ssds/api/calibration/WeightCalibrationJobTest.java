package com.example.ssds.api.calibration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.ssds.api.calibration.dto.WeightCalibrationInterpretRequest;
import com.example.ssds.infra.entity.CalibrationReport;
import com.example.ssds.infra.repository.CalibrationReportRepository;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WeightCalibrationJobTest {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Test
    void januaryRunReadsPreviousYearsFourthQuarter() {
        CalibrationReportRepository reports = mock(CalibrationReportRepository.class);
        SceneClassificationLogRepository scenes = mock(SceneClassificationLogRepository.class);
        WeightCalibrationService service = mock(WeightCalibrationService.class);
        CalibrationReport report = CalibrationReport.builder().id(7L).quarter("2025Q4").build();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), TAIPEI);
        when(reports.findByQuarter("2025Q4")).thenReturn(Optional.of(report));
        when(scenes.findByCreatedAtBetween(any(), any())).thenReturn(List.of());

        new WeightCalibrationJob(reports, scenes, service, clock).run();

        verify(reports).findByQuarter("2025Q4");
        verify(scenes).findByCreatedAtBetween(
                Instant.parse("2025-09-30T16:00:00Z"),
                Instant.parse("2025-12-31T16:00:00Z"));
        verify(service).interpret(eq(7L), any(WeightCalibrationInterpretRequest.class));
    }
}
