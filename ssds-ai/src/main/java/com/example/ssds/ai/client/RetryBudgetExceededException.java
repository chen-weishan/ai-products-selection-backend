package com.example.ssds.ai.client;

import java.time.OffsetDateTime;

public class RetryBudgetExceededException extends RuntimeException {
    private final OffsetDateTime resetAt;
    public RetryBudgetExceededException(OffsetDateTime resetAt){super("重試與臨時任務池已耗盡，可用額度重置時間："+resetAt);this.resetAt=resetAt;}
    public OffsetDateTime resetAt(){return resetAt;}
}
