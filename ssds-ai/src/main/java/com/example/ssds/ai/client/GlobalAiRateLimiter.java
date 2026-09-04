package com.example.ssds.ai.client;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** §6.7.3：單一 JVM 內所有 A／B 軌 LLM 呼叫共用的每分鐘限流器。 */
@Component
public class GlobalAiRateLimiter {
    private static final Duration REFRESH_PERIOD = Duration.ofMinutes(1);
    private static final Duration PERMISSION_WAIT = Duration.ofMinutes(1);

    private final RateLimiter rateLimiter;

    @Autowired
    public GlobalAiRateLimiter(
            @Value("${ai.rate-limit-per-minute:20}") int requestsPerMinute) {
        this(requestsPerMinute, PERMISSION_WAIT);
    }

    GlobalAiRateLimiter(int requestsPerMinute, Duration permissionWait) {
        if (requestsPerMinute <= 0) {
            throw new IllegalArgumentException("LLM 每分鐘請求上限必須大於 0");
        }
        if (permissionWait == null || permissionWait.isNegative()) {
            throw new IllegalArgumentException("LLM 限流等待時間不得為負數");
        }
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(requestsPerMinute)
                .limitRefreshPeriod(REFRESH_PERIOD)
                .timeoutDuration(permissionWait)
                .build();
        this.rateLimiter = RateLimiter.of("global-llm", config);
    }

    public void acquire() {
        if (!rateLimiter.acquirePermission()) {
            throw new AiRateLimitException("應用層 LLM 每分鐘請求上限已達，請稍後再試", null);
        }
    }

    public static GlobalAiRateLimiter unrestrictedForTests() {
        return new GlobalAiRateLimiter(1_000_000, Duration.ZERO);
    }
}
