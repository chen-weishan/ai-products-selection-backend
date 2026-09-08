package com.example.ssds.ai.client;

import com.example.ssds.core.domain.AiTaskType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** v3.0 三個每日請求數預算池；快取命中不消耗 request quota。 */
@Component
public class DailyAiBudget {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");

    private final int dailyQuota;
    private final EnumMap<AiTaskType.BudgetPool, Double> shares =
            new EnumMap<>(AiTaskType.BudgetPool.class);
    private final EnumMap<AiTaskType.BudgetPool, Integer> used =
            new EnumMap<>(AiTaskType.BudgetPool.class);
    private final EnumMap<AiTaskType.BudgetPool, Integer> cacheHits =
            new EnumMap<>(AiTaskType.BudgetPool.class);
    private final Clock clock;
    private LocalDate usageDate;

    @Autowired
    public DailyAiBudget(
            @Value("${ai.quota-daily:1000}") int dailyQuota,
            @Value("${ai.quota-share-track-a:0.7}") double trackAShare,
            @Value("${ai.quota-share-track-b:0.2}") double trackBShare,
            @Value("${ai.quota-share-retry:0.1}") double retryShare) {
        this(dailyQuota, trackAShare, trackBShare, retryShare, Clock.system(BUSINESS_ZONE));
    }

    DailyAiBudget(
            int dailyQuota,
            double trackAShare,
            double trackBShare,
            double retryShare,
            Clock clock) {
        if (dailyQuota < 0) throw new IllegalArgumentException("AI 每日配額不得為負數");
        validateShare(trackAShare);
        validateShare(trackBShare);
        validateShare(retryShare);
        if (trackAShare + trackBShare + retryShare > 1.000001d) {
            throw new IllegalArgumentException("AI 三個預算池比例總和不得大於 1");
        }
        this.dailyQuota = dailyQuota;
        this.clock = clock;
        shares.put(AiTaskType.BudgetPool.TRACK_A, trackAShare);
        shares.put(AiTaskType.BudgetPool.TRACK_B, trackBShare);
        shares.put(AiTaskType.BudgetPool.RETRY, retryShare);
        reset(LocalDate.now(clock.withZone(BUSINESS_ZONE)));
    }

    public synchronized void acquire(AiTaskType.BudgetPool requestedPool) {
        acquire(requestedPool, false);
    }

    public synchronized void acquire(
            AiTaskType.BudgetPool requestedPool, boolean retryAttempt) {
        rollDateIfNeeded();
        AiTaskType.BudgetPool pool = AiBudgetExecutionContext.resolve(requestedPool, retryAttempt);
        int limit = limit(pool);
        if (used.get(pool) >= limit) throw new AiBudgetExceededException(pool, resetAt());
        used.put(pool, used.get(pool) + 1);
        AiBudgetExecutionContext.requestConsumed(pool);
    }

    public synchronized void recordCacheHit(AiTaskType.BudgetPool requestedPool) {
        rollDateIfNeeded();
        AiTaskType.BudgetPool pool = AiBudgetExecutionContext.resolve(requestedPool);
        cacheHits.put(pool, cacheHits.get(pool) + 1);
        AiBudgetExecutionContext.cacheHit();
    }

    public synchronized Snapshot snapshot() {
        rollDateIfNeeded();
        List<PoolSnapshot> pools = shares.keySet().stream()
                .map(pool -> {
                    int limit = limit(pool);
                    int consumed = used.get(pool);
                    BudgetStatus status = consumed >= limit
                            ? BudgetStatus.EXHAUSTED
                            : consumed >= Math.ceil(limit * 0.8d)
                                    ? BudgetStatus.WARNING : BudgetStatus.OK;
                    return new PoolSnapshot(
                            pool, shares.get(pool), limit, consumed, cacheHits.get(pool), status);
                })
                .toList();
        return new Snapshot(dailyQuota, resetAt(), "CONFIGURED", pools);
    }

    /** 應用重啟時由已落庫的 ai_task 計數還原；取較大值避免覆蓋啟動後的新請求。 */
    public synchronized void restore(
            AiTaskType.BudgetPool pool, int persistedUsed, int persistedCacheHits) {
        rollDateIfNeeded();
        used.put(pool, Math.max(used.get(pool), Math.max(0, persistedUsed)));
        cacheHits.put(pool, Math.max(cacheHits.get(pool), Math.max(0, persistedCacheHits)));
    }

    private int limit(AiTaskType.BudgetPool pool) {
        return Math.max(0, (int) Math.floor(dailyQuota * shares.get(pool)));
    }

    private void rollDateIfNeeded() {
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        if (!today.equals(usageDate)) reset(today);
    }

    private void reset(LocalDate date) {
        usageDate = date;
        for (AiTaskType.BudgetPool pool : AiTaskType.BudgetPool.values()) {
            used.put(pool, 0);
            cacheHits.put(pool, 0);
        }
    }

    private OffsetDateTime resetAt() {
        return usageDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).toOffsetDateTime();
    }

    private static void validateShare(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("AI 預算池比例必須介於 0 與 1");
        }
    }

    public enum BudgetStatus { OK, WARNING, EXHAUSTED }

    public record PoolSnapshot(
            AiTaskType.BudgetPool pool,
            double share,
            int limit,
            int used,
            int cacheHits,
            BudgetStatus status) {}

    public record Snapshot(
            int dailyQuota,
            OffsetDateTime resetAt,
            String resetSource,
            List<PoolSnapshot> pools) {}
}
