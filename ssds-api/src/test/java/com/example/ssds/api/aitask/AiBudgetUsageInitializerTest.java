package com.example.ssds.api.aitask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.ai.client.DailyAiBudget;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.infra.repository.AiTaskRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiBudgetUsageInitializerTest {
    @Test
    void restoresFullAnalysisPrimaryAndRetryRequestsIntoSeparatePools() {
        AiTaskRepository tasks = mock(AiTaskRepository.class);
        DailyAiBudget budget = new DailyAiBudget(100, 0.7, 0.2, 0.1);
        when(tasks.summarizeBudgetUsageSince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.<Object[]>of(new Object[] {
                    AiTaskType.BudgetPool.TRACK_A, 9L, 3L, 0L
                }));

        new AiBudgetUsageInitializer(tasks, budget).restoreTodayUsage();

        DailyAiBudget.PoolSnapshot trackA = pool(budget, AiTaskType.BudgetPool.TRACK_A);
        DailyAiBudget.PoolSnapshot retry = pool(budget, AiTaskType.BudgetPool.RETRY);
        assertEquals(6, trackA.used());
        assertEquals(3, retry.used());
    }

    private static DailyAiBudget.PoolSnapshot pool(
            DailyAiBudget budget, AiTaskType.BudgetPool pool) {
        return budget.snapshot().pools().stream()
                .filter(value -> value.pool() == pool)
                .findFirst()
                .orElseThrow();
    }
}
