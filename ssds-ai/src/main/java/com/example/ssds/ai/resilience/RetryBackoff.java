package com.example.ssds.ai.resilience;

public final class RetryBackoff {
    private static final long INITIAL_DELAY_MILLIS = 1_000L;
    private static final int MAX_SHIFT = 10;

    private RetryBackoff() {}

    public static long delayMillis(int retryIndex) {
        return INITIAL_DELAY_MILLIS << Math.min(Math.max(0, retryIndex), MAX_SHIFT);
    }
}
