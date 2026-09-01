package com.example.ssds.ai.client;

import com.example.ssds.core.domain.AiTaskType;
import java.time.OffsetDateTime;

/** 每日 AI 預算池已耗盡；呼叫端應停止送出並保留待重跑項目。 */
public class AiBudgetExceededException extends RuntimeException {
    private final AiTaskType.BudgetPool pool;
    private final OffsetDateTime resetAt;

    public AiBudgetExceededException(AiTaskType.BudgetPool pool, OffsetDateTime resetAt) {
        super("AI 預算池 " + pool + " 已耗盡，可用額度重置時間：" + resetAt);
        this.pool = pool;
        this.resetAt = resetAt;
    }

    public AiTaskType.BudgetPool pool() {
        return pool;
    }

    public OffsetDateTime resetAt() {
        return resetAt;
    }
}
