package com.example.ssds.ai.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GlobalAiRateLimiterTest {
    @Test
    void rejectsCallsBeyondTheConfiguredMinuteLimit() {
        GlobalAiRateLimiter limiter = new GlobalAiRateLimiter(1, Duration.ZERO);

        assertDoesNotThrow(limiter::acquire);
        assertThrows(AiRateLimitException.class, limiter::acquire);
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GlobalAiRateLimiter(0, Duration.ZERO));
    }
}
