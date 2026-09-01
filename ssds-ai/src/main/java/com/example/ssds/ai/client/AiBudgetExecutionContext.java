package com.example.ssds.ai.client;

import com.example.ssds.core.domain.AiTaskType;
import java.util.EnumMap;

/** 將非同步 AI task 的預算池與逐品項請求統計限制在目前 worker thread。 */
public final class AiBudgetExecutionContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private AiBudgetExecutionContext() {}

    public static void begin(AiTaskType.BudgetPool pool) {
        CURRENT.set(new State(pool));
    }

    public static AiTaskType.BudgetPool resolve(AiTaskType.BudgetPool fallback) {
        return resolve(fallback, false);
    }

    public static AiTaskType.BudgetPool resolve(
            AiTaskType.BudgetPool fallback, boolean retryAttempt) {
        if (retryAttempt) return AiTaskType.BudgetPool.RETRY;
        State state = CURRENT.get();
        return state == null ? fallback : state.pool;
    }

    static void requestConsumed(AiTaskType.BudgetPool pool) {
        State state = CURRENT.get();
        if (state != null) state.requests.merge(pool, 1, Integer::sum);
    }

    static void cacheHit() {
        State state = CURRENT.get();
        if (state != null) state.cacheHits++;
    }

    public static Metrics metrics() {
        State state = CURRENT.get();
        return state == null
                ? new Metrics(0, 0, 0)
                : new Metrics(
                        state.requests.values().stream().mapToInt(Integer::intValue).sum(),
                        state.requests.getOrDefault(AiTaskType.BudgetPool.RETRY, 0),
                        state.cacheHits);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Metrics(int requests, int retryPoolRequests, int cacheHits) {
        public Metrics(int requests, int cacheHits) {
            this(requests, 0, cacheHits);
        }
    }

    private static final class State {
        private final AiTaskType.BudgetPool pool;
        private final EnumMap<AiTaskType.BudgetPool, Integer> requests =
                new EnumMap<>(AiTaskType.BudgetPool.class);
        private int cacheHits;

        private State(AiTaskType.BudgetPool pool) {
            this.pool = pool;
        }
    }
}
