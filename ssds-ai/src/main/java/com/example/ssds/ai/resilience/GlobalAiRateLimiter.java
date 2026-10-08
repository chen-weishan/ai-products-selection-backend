package com.example.ssds.ai.resilience;

import com.example.ssds.core.domain.AiTaskType;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** §6.7.3：單一 JVM 內所有 A／B 軌 LLM 呼叫共用的平滑限流器。 */
@Component
public class GlobalAiRateLimiter {
    private static final Duration REFRESH_PERIOD = Duration.ofMinutes(1);
    private static final Duration PERMISSION_WAIT = Duration.ofMinutes(1);

    private volatile RateLimiter globalRateLimiter;
    private volatile RateLimiter trendRateLimiter;

    @Autowired
    public GlobalAiRateLimiter(
            @Value("${ai.rate-limit-per-minute:20}") int requestsPerMinute,
            @Value("${ai.trend.rate-limit-per-minute:5}") int trendRequestsPerMinute) {
        this(requestsPerMinute, trendRequestsPerMinute, REFRESH_PERIOD, PERMISSION_WAIT);
    }

    GlobalAiRateLimiter(int requestsPerMinute, Duration permissionWait) {
        this(requestsPerMinute, requestsPerMinute, REFRESH_PERIOD, permissionWait);
    }

    GlobalAiRateLimiter(
            int requestsPerMinute,
            int trendRequestsPerMinute,
            Duration ratePeriod,
            Duration permissionWait) {
        validateRate("LLM", requestsPerMinute);
        validateRate("Agent 5", trendRequestsPerMinute);
        if (ratePeriod == null || ratePeriod.isZero() || ratePeriod.isNegative()) {
            throw new IllegalArgumentException("LLM 限流週期必須大於 0");
        }
        if (permissionWait == null || permissionWait.isNegative()) {
            throw new IllegalArgumentException("LLM 限流等待時間不得為負數");
        }
        this.globalRateLimiter = smoothLimiter(
                "global-llm", requestsPerMinute, ratePeriod, permissionWait);
        this.trendRateLimiter = smoothLimiter(
                "trend-interpret", trendRequestsPerMinute, ratePeriod, permissionWait);
    }

    private static void validateRate(String name, int requestsPerPeriod) {
        if (requestsPerPeriod <= 0) {
            throw new IllegalArgumentException(name + " 每分鐘請求上限必須大於 0");
        }
    }

    private static RateLimiter smoothLimiter(
            String name, int requestsPerPeriod, Duration ratePeriod, Duration permissionWait) {
        Duration interval = ratePeriod.dividedBy(requestsPerPeriod);
        if (interval.isZero()) {
            throw new IllegalArgumentException("LLM 限流間隔必須大於 0");
        }
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(interval)
                .timeoutDuration(permissionWait)
                .build();
        return RateLimiter.of(name, config);
    }

    public void acquire() {
        acquire(globalRateLimiter, "應用層 LLM 每分鐘請求上限已達，請稍後再試");
    }

    public void acquire(AiTaskType taskType) {
        Objects.requireNonNull(taskType, "AI 任務類型不得為 null");
        if (taskType == AiTaskType.TREND_INTERPRET) {
            // Agent 5 的 Prompt 較大，先等專屬 TPM 保護，再占用全域 permit。
            acquire(trendRateLimiter, "Agent 5 LLM 每分鐘請求上限已達，請稍後再試");
        }
        acquire();
    }

    /** 後續請求立即使用新速率；已取得的 permit 不會被追回。 */
    public synchronized void reconfigure(int requestsPerMinute, int trendRequestsPerMinute) {
        validateRate("LLM", requestsPerMinute);
        validateRate("Agent 5", trendRequestsPerMinute);
        globalRateLimiter = smoothLimiter(
                "global-llm-runtime", requestsPerMinute, REFRESH_PERIOD, PERMISSION_WAIT);
        trendRateLimiter = smoothLimiter(
                "trend-interpret-runtime", trendRequestsPerMinute, REFRESH_PERIOD, PERMISSION_WAIT);
    }

    private static void acquire(RateLimiter limiter, String message) {
        if (!limiter.acquirePermission()) {
            throw new AiRateLimitException(message, null);
        }
    }

    public static GlobalAiRateLimiter unrestrictedForTests() {
        return new GlobalAiRateLimiter(
                1_000_000, 1_000_000, REFRESH_PERIOD, Duration.ZERO);
    }
}
