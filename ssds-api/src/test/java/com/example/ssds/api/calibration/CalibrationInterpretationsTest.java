package com.example.ssds.api.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.aitask.dto.AiTaskResponse;
import com.example.ssds.api.aitask.service.AiTaskService;
import com.example.ssds.core.domain.AiTaskType;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalibrationInterpretationsTest {

    private final AiTaskService aiTaskService = mock(AiTaskService.class);
    private final CalibrationInterpretations interpretations = new CalibrationInterpretations(aiTaskService);

    @Test
    void createsWeightCalibrationTaskForTheReport() {
        AiTaskResponse task = mock(AiTaskResponse.class);
        when(task.taskId()).thenReturn(42L);
        when(aiTaskService.create(any())).thenReturn(task);

        assertThat(interpretations.request(7L, "2026Q3")).isEqualTo(42L);

        verify(aiTaskService).create(argThat(request ->
                request.taskType() == AiTaskType.WEIGHT_CALIBRATION
                        && request.calibrationReportIds().equals(List.of(7L))
                        && request.productIds().isEmpty()
                        && !request.forceRefresh()));
    }

    @Test
    void failureIsSwallowedSoTheCommittedReportIsUnaffected() {
        when(aiTaskService.create(any())).thenThrow(new IllegalStateException("budget"));

        assertThat(interpretations.request(7L, "2026Q3")).isNull();
    }
}
