package com.example.ssds.ai.client;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.core.domain.AiTaskType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DailyAiBudgetTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-31T01:00:00Z"), ZoneId.of("Asia/Taipei"));

    @AfterEach
    void clearContext() {
        AiBudgetExecutionContext.clear();
    }

    @Test
    void isolatesSeventyTwentyTenPoolsAndStopsAtLimit() {
        DailyAiBudget budget = new DailyAiBudget(10, 0.7, 0.2, 0.1, CLOCK);

        for (int index = 0; index < 7; index++) budget.acquire(AiTaskType.BudgetPool.TRACK_A);
        assertThrows(AiBudgetExceededException.class,
                () -> budget.acquire(AiTaskType.BudgetPool.TRACK_A));

        assertDoesNotThrow(() -> {
            budget.acquire(AiTaskType.BudgetPool.TRACK_B);
            budget.acquire(AiTaskType.BudgetPool.TRACK_B);
            budget.acquire(AiTaskType.BudgetPool.RETRY);
        });
        assertEquals(DailyAiBudget.BudgetStatus.EXHAUSTED,
                budget.snapshot().pools().getFirst().status());
    }

    @Test
    void cacheHitDoesNotConsumeQuotaAndTaskContextCanUseRetryPool() {
        DailyAiBudget budget = new DailyAiBudget(10, 0.7, 0.2, 0.1, CLOCK);
        AiBudgetExecutionContext.begin(AiTaskType.BudgetPool.RETRY);

        budget.recordCacheHit(AiTaskType.BudgetPool.TRACK_A);
        budget.acquire(AiTaskType.BudgetPool.TRACK_A);

        DailyAiBudget.PoolSnapshot retry = budget.snapshot().pools().stream()
                .filter(value -> value.pool() == AiTaskType.BudgetPool.RETRY)
                .findFirst().orElseThrow();
        assertEquals(1, retry.used());
        assertEquals(1, retry.cacheHits());
        assertEquals(new AiBudgetExecutionContext.Metrics(1, 1, 1), AiBudgetExecutionContext.metrics());
    }

    @Test
    void fullAnalysisRetryConsumesRetryPoolInsteadOfTrackA() {
        DailyAiBudget budget = new DailyAiBudget(10, 0.7, 0.2, 0.1, CLOCK);
        AiBudgetExecutionContext.begin(AiTaskType.BudgetPool.TRACK_A);

        budget.acquire(AiTaskType.BudgetPool.TRACK_A, false);
        budget.acquire(AiTaskType.BudgetPool.TRACK_A, true);

        DailyAiBudget.PoolSnapshot trackA = budget.snapshot().pools().stream()
                .filter(value -> value.pool() == AiTaskType.BudgetPool.TRACK_A)
                .findFirst().orElseThrow();
        DailyAiBudget.PoolSnapshot retry = budget.snapshot().pools().stream()
                .filter(value -> value.pool() == AiTaskType.BudgetPool.RETRY)
                .findFirst().orElseThrow();
        assertEquals(1, trackA.used());
        assertEquals(1, retry.used());
        assertEquals(new AiBudgetExecutionContext.Metrics(2, 1, 0), AiBudgetExecutionContext.metrics());
    }

    @Test
    void sourcingPrimaryUsesTrackBAndSourcingRetryUsesRetryPool() {
        DailyAiBudget budget = new DailyAiBudget(10, 0.7, 0.2, 0.1, CLOCK);
        TrackBSourcingBudget sourcing = new TrackBSourcingBudget(budget);

        sourcing.acquire(false);
        sourcing.acquire(true);

        DailyAiBudget.PoolSnapshot trackB = budget.snapshot().pools().stream()
                .filter(value -> value.pool() == AiTaskType.BudgetPool.TRACK_B)
                .findFirst().orElseThrow();
        DailyAiBudget.PoolSnapshot retry = budget.snapshot().pools().stream()
                .filter(value -> value.pool() == AiTaskType.BudgetPool.RETRY)
                .findFirst().orElseThrow();
        assertEquals(1, trackB.used());
        assertEquals(1, retry.used());
    }

    @Test
    void calibrationUsesRetryPoolFromFirstRequest() {
        DailyAiBudget budget = new DailyAiBudget(10, 0.7, 0.2, 0.1, CLOCK);

        budget.acquire(AiTaskType.WEIGHT_CALIBRATION.budgetPool(), false);

        DailyAiBudget.PoolSnapshot retry = budget.snapshot().pools().stream()
                .filter(value -> value.pool() == AiTaskType.BudgetPool.RETRY)
                .findFirst().orElseThrow();
        assertEquals(1, retry.used());
    }

    @Test
    void reportsWarningAtEightyPercent() {
        DailyAiBudget budget = new DailyAiBudget(10, 1.0, 0, 0, CLOCK);
        for (int index = 0; index < 8; index++) budget.acquire(AiTaskType.BudgetPool.TRACK_A);
        assertEquals(DailyAiBudget.BudgetStatus.WARNING,
                budget.snapshot().pools().getFirst().status());
    }

    @Test
    void restoresPersistedUsageWithoutReducingLiveCounter() {
        DailyAiBudget budget = new DailyAiBudget(10, 0.7, 0.2, 0.1, CLOCK);
        budget.acquire(AiTaskType.BudgetPool.TRACK_A);
        budget.restore(AiTaskType.BudgetPool.TRACK_A, 5, 3);
        budget.restore(AiTaskType.BudgetPool.TRACK_A, 2, 1);

        DailyAiBudget.PoolSnapshot trackA = budget.snapshot().pools().getFirst();
        assertEquals(5, trackA.used());
        assertEquals(3, trackA.cacheHits());
    }
}
