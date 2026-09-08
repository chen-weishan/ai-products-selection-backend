package com.example.ssds.ai.client;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Agent 6 的隔離每日配額；不會消耗或阻斷 A 軌池。 */
@Component
public class TrackBSourcingBudget {
    private final DailyAiBudget budget;

    @Autowired
    public TrackBSourcingBudget(DailyAiBudget budget) {
        this.budget = budget;
    }

    public TrackBSourcingBudget(int dailyQuota, double share) {
        this(new DailyAiBudget(dailyQuota, 0, share, 0, java.time.Clock.systemUTC()));
    }

    public void acquire() {
        acquire(false);
    }

    public void acquire(boolean retryAttempt) {
        try {
            budget.acquire(
                    com.example.ssds.core.domain.AiTaskType.BudgetPool.TRACK_B,
                    retryAttempt);
        } catch (AiBudgetExceededException exception) {
            if (exception.pool()
                    == com.example.ssds.core.domain.AiTaskType.BudgetPool.RETRY) {
                throw exception;
            }
            throw new SourcingBudgetExceededException(exception.resetAt());
        }
    }
}
