package com.example.ssds.api.trend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import com.example.ssds.api.aitask.AiTaskService;
import com.example.ssds.api.aitask.dto.*;
import com.example.ssds.core.domain.*;
import org.junit.jupiter.api.Test;

class TrendInterpretationControllerTest {
    @Test
    void manualInterpretationCreatesRetryPoolTask() {
        TrendInterpretationService service = mock(TrendInterpretationService.class);
        AiTaskService taskService = mock(AiTaskService.class);
        AiTaskResponse task = mock(AiTaskResponse.class);
        when(taskService.create(any())).thenReturn(task);

        var response = new TrendInterpretationController(service, taskService)
                .interpret(31L, true);

        assertEquals(202, response.getStatusCode().value());
        verify(taskService).create(argThat(request ->
                request.taskType() == AiTaskType.TREND_INTERPRET
                        && request.keywordIds().equals(java.util.List.of(31L))
                        && request.forceRefresh()));
        verifyNoInteractions(service);
    }
}
