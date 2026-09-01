package com.example.ssds.api.aitask;

import com.example.ssds.ai.client.DailyAiBudget;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.infra.repository.AiTaskRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 從 ai_task 還原今日三池用量，避免服務重啟使每日 quota 歸零。 */
@Component
public class AiBudgetUsageInitializer {
    private static final Logger log = LoggerFactory.getLogger(AiBudgetUsageInitializer.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
    private final AiTaskRepository tasks;
    private final DailyAiBudget budget;

    public AiBudgetUsageInitializer(AiTaskRepository tasks, DailyAiBudget budget) {
        this.tasks = tasks;
        this.budget = budget;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreTodayUsage() {
        var since = LocalDate.now(BUSINESS_ZONE).atStartOfDay(BUSINESS_ZONE).toInstant();
        EnumMap<AiTaskType.BudgetPool, Integer> requests =
                new EnumMap<>(AiTaskType.BudgetPool.class);
        EnumMap<AiTaskType.BudgetPool, Integer> cacheHits =
                new EnumMap<>(AiTaskType.BudgetPool.class);
        for (Object[] row : tasks.summarizeBudgetUsageSince(since)) {
            AiTaskType.BudgetPool taskPool = (AiTaskType.BudgetPool) row[0];
            int total = ((Number) row[1]).intValue();
            int retry = ((Number) row[2]).intValue();
            int taskCacheHits = ((Number) row[3]).intValue();
            requests.merge(taskPool, Math.max(0, total - retry), Integer::sum);
            requests.merge(AiTaskType.BudgetPool.RETRY, retry, Integer::sum);
            cacheHits.merge(taskPool, taskCacheHits, Integer::sum);
        }
        for (AiTaskType.BudgetPool pool : AiTaskType.BudgetPool.values()) {
            int used = requests.getOrDefault(pool, 0);
            int persistedCacheHits = cacheHits.getOrDefault(pool, 0);
            budget.restore(pool, used, persistedCacheHits);
            log.info("AI budget restored: pool={}, used={}, cacheHits={}", pool, used, persistedCacheHits);
        }
    }
}
