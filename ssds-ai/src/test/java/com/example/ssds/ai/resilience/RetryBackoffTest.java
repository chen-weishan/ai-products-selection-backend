package com.example.ssds.ai.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RetryBackoffTest {
    @Test
    void usesOneTwoFourSecondExponentialDelays() {
        assertEquals(1_000L, RetryBackoff.delayMillis(0));
        assertEquals(2_000L, RetryBackoff.delayMillis(1));
        assertEquals(4_000L, RetryBackoff.delayMillis(2));
    }

    @Test
    void clampsInvalidAndExcessiveIndexes() {
        assertEquals(1_000L, RetryBackoff.delayMillis(-1));
        assertEquals(1_024_000L, RetryBackoff.delayMillis(100));
    }
}
