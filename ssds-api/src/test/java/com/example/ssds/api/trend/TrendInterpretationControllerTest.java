package com.example.ssds.api.trend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import com.example.ssds.api.aitask.service.AiTaskService;
import com.example.ssds.api.aitask.dto.*;
import com.example.ssds.api.trend.dto.TrendInterpretationResponse;
import com.example.ssds.core.domain.*;
import java.time.OffsetDateTime;
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

    @Test
    void latestExposesModelPromptAndFallbackMetadata() {
        TrendInterpretationService service = mock(TrendInterpretationService.class);
        AiTaskService taskService = mock(AiTaskService.class);
        TrendInterpretationResponse latest = new TrendInterpretationResponse(
                31L,
                HeatStage.PLATEAU,
                2,
                42,
                true,
                "AI_UNAVAILABLE",
                false,
                "rule-fallback",
                "MODEL_NUMERIC",
                "trend-v4",
                0,
                OffsetDateTime.parse("2026-09-23T06:00:00+08:00"));
        when(service.latest(31L)).thenReturn(latest);

        var response = new TrendInterpretationController(service, taskService).latest(31L);

        assertTrue(response.success());
        assertSame(latest, response.data());
        assertAll(
                () -> assertTrue(response.data().fallbackApplied()),
                () -> assertEquals("AI_UNAVAILABLE", response.data().fallbackReason()),
                () -> assertEquals("rule-fallback", response.data().model()),
                () -> assertEquals("trend-v4", response.data().promptVersion()));
        verifyNoInteractions(taskService);
    }
}
