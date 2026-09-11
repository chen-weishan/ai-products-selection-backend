package com.example.ssds.api.aitask;

import com.example.ssds.ai.client.AiBudgetUsageRecorder;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.infra.repository.AiBudgetUsageDailyRepository;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes every consumed request/cache hit even when no ai_task exists or its transaction fails. */
@Component
public class PersistentAiBudgetUsageRecorder implements AiBudgetUsageRecorder {
    private final AiBudgetUsageDailyRepository usages;

    public PersistentAiBudgetUsageRecorder(AiBudgetUsageDailyRepository usages) {
        this.usages = usages;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            LocalDate usageDate,
            AiTaskType.BudgetPool pool,
            int requestDelta,
            int cacheHitDelta) {
        usages.increment(usageDate, pool.name(), requestDelta, cacheHitDelta);
    }
}
