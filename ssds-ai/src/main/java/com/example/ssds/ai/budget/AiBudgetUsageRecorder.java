package com.example.ssds.ai.budget;

import com.example.ssds.core.domain.AiTaskType;
import java.time.LocalDate;

/** Persists daily AI usage independently from task-level execution records. */
@FunctionalInterface
public interface AiBudgetUsageRecorder {
    AiBudgetUsageRecorder NO_OP = (usageDate, pool, requestDelta, cacheHitDelta) -> {};

    void record(
            LocalDate usageDate,
            AiTaskType.BudgetPool pool,
            int requestDelta,
            int cacheHitDelta);
}
