package com.example.ssds.api.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ssds.api.aitask.AiTaskService;
import com.example.ssds.api.aitask.dto.*;
import com.example.ssds.api.calibration.dto.WeightCalibrationTaskRequest;
import com.example.ssds.core.domain.AiTaskType;
import org.junit.jupiter.api.Test;

class WeightCalibrationControllerTest {
    @Test
    void manualInterpretationCreatesCalibrationTask() {
        AiTaskService taskService = mock(AiTaskService.class);
        when(taskService.create(any())).thenReturn(mock(AiTaskResponse.class));

        var response = new WeightCalibrationController(taskService)
                .interpret(7L, new WeightCalibrationTaskRequest(true));

        assertEquals(202, response.getStatusCode().value());
        verify(taskService).create(argThat(request ->
                request.taskType() == AiTaskType.WEIGHT_CALIBRATION
                        && request.calibrationReportIds().equals(java.util.List.of(7L))
                        && request.forceRefresh()));
    }
}
