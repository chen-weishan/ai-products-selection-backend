package com.example.ssds.ai.resilience;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import com.example.ssds.core.domain.AiTaskType;
import org.junit.jupiter.api.Test;

class GlobalAiRateLimiterTest {
    @Test
    void rejectsCallsBeyondTheConfiguredMinuteLimit() {
        GlobalAiRateLimiter limiter = new GlobalAiRateLimiter(1, Duration.ZERO);

        assertDoesNotThrow(() -> limiter.acquire());
        assertThrows(AiRateLimitException.class, () -> limiter.acquire());
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GlobalAiRateLimiter(0, Duration.ZERO));
    }

    @Test
    void appliesStricterTrendLimitWithoutBlockingOtherTaskTypes() throws InterruptedException {
        GlobalAiRateLimiter limiter = new GlobalAiRateLimiter(
                100, 1, Duration.ofMillis(100), Duration.ZERO);

        assertDoesNotThrow(() -> limiter.acquire(AiTaskType.TREND_INTERPRET));
        Thread.sleep(10);
        assertThrows(
                AiRateLimitException.class,
                () -> limiter.acquire(AiTaskType.TREND_INTERPRET));
        assertDoesNotThrow(() -> limiter.acquire(AiTaskType.SCENE_CLASSIFY));
    }

    @Test
    void rejectsNonPositiveTrendLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GlobalAiRateLimiter(
                        20, 0, Duration.ofMinutes(1), Duration.ZERO));
    }
}
