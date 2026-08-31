package com.example.ssds.ai.client;

import java.time.OffsetDateTime;

public class SourcingBudgetExceededException extends RuntimeException {
    private final OffsetDateTime resetAt;
    public SourcingBudgetExceededException(OffsetDateTime resetAt) {
        super("B 軌探索池已耗盡，可用額度重置時間：" + resetAt);
        this.resetAt = resetAt;
    }
    public OffsetDateTime resetAt() { return resetAt; }
}
