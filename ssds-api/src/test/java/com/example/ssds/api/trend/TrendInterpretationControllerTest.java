package com.example.ssds.api.trend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.ai.client.AiBudgetExecutionContext;
import com.example.ssds.api.trend.dto.TrendInterpretationResponse;
import com.example.ssds.core.domain.AiTaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TrendInterpretationControllerTest {
    @AfterEach
    void clearBudgetContext() {
        AiBudgetExecutionContext.clear();
    }

    @Test
    void manualInterpretationUsesRetryPoolAndClearsContextAfterward() {
        TrendInterpretationService service = mock(TrendInterpretationService.class);
        TrendInterpretationResponse response = mock(TrendInterpretationResponse.class);
        when(service.interpret(31L, true)).thenAnswer(ignored -> {
            assertEquals(
                    AiTaskType.BudgetPool.RETRY,
                    AiBudgetExecutionContext.resolve(AiTaskType.BudgetPool.TRACK_A));
            return response;
        });

        new TrendInterpretationController(service).interpret(31L, true);

        assertEquals(
                AiTaskType.BudgetPool.TRACK_A,
                AiBudgetExecutionContext.resolve(AiTaskType.BudgetPool.TRACK_A));
    }
}
