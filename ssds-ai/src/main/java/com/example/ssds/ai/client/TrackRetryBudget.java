package com.example.ssds.ai.client;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Agent 7 使用的 RETRY 池每日請求上限；快取命中不呼叫 acquire。 */
@Component
public class TrackRetryBudget {
    private final DailyAiBudget budget;
    @Autowired
    public TrackRetryBudget(DailyAiBudget budget){this.budget=budget;}
    public TrackRetryBudget(int dailyQuota,double share){this(new DailyAiBudget(dailyQuota,0,0,share,java.time.Clock.systemUTC()));}
    public void acquire(){
        try { budget.acquire(com.example.ssds.core.domain.AiTaskType.BudgetPool.RETRY); }
        catch(AiBudgetExceededException exception){throw new RetryBudgetExceededException(exception.resetAt());}
    }
}
