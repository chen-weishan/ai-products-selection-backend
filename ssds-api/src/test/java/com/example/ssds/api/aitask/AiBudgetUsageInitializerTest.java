package com.example.ssds.api.aitask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.ai.budget.DailyAiBudget;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.infra.entity.AiBudgetUsageDaily;
import com.example.ssds.infra.repository.AiBudgetUsageDailyRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiBudgetUsageInitializerTest {
    @Test
    void restoresFullAnalysisPrimaryAndRetryRequestsIntoSeparatePools() {
        AiBudgetUsageDailyRepository usages = mock(AiBudgetUsageDailyRepository.class);
        DailyAiBudget budget = new DailyAiBudget(100, 0.7, 0.2, 0.1);
        when(usages.findByUsageDate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        usage(AiTaskType.BudgetPool.TRACK_A, 6, 2),
                        usage(AiTaskType.BudgetPool.RETRY, 3, 1)));

        new AiBudgetUsageInitializer(usages, budget).restoreTodayUsage();

        DailyAiBudget.PoolSnapshot trackA = pool(budget, AiTaskType.BudgetPool.TRACK_A);
        DailyAiBudget.PoolSnapshot retry = pool(budget, AiTaskType.BudgetPool.RETRY);
        assertEquals(6, trackA.used());
        assertEquals(3, retry.used());
        assertEquals(2, trackA.cacheHits());
        assertEquals(1, retry.cacheHits());
    }

    private static AiBudgetUsageDaily usage(
            AiTaskType.BudgetPool pool, int requests, int cacheHits) {
        return AiBudgetUsageDaily.builder()
                .usageDate(LocalDate.now())
                .budgetPool(pool)
                .requestCount(requests)
                .cacheHitCount(cacheHits)
                .build();
    }

    private static DailyAiBudget.PoolSnapshot pool(
            DailyAiBudget budget, AiTaskType.BudgetPool pool) {
        return budget.snapshot().pools().stream()
                .filter(value -> value.pool() == pool)
                .findFirst()
                .orElseThrow();
    }
}
