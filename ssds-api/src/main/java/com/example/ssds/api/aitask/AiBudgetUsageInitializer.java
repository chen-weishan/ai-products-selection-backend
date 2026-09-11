package com.example.ssds.api.aitask;

import com.example.ssds.ai.budget.DailyAiBudget;
import com.example.ssds.infra.repository.AiBudgetUsageDailyRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 從每日用量帳本還原三池計數；帳本涵蓋 ai_task 與直接呼叫。 */
@Component
public class AiBudgetUsageInitializer {
    private static final Logger log = LoggerFactory.getLogger(AiBudgetUsageInitializer.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
    private final AiBudgetUsageDailyRepository usages;
    private final DailyAiBudget budget;

    public AiBudgetUsageInitializer(AiBudgetUsageDailyRepository usages, DailyAiBudget budget) {
        this.usages = usages;
        this.budget = budget;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreTodayUsage() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        for (var usage : usages.findByUsageDate(today)) {
            budget.restore(
                    usage.getBudgetPool(), usage.getRequestCount(), usage.getCacheHitCount());
            log.info(
                    "AI budget restored: pool={}, used={}, cacheHits={}",
                    usage.getBudgetPool(), usage.getRequestCount(), usage.getCacheHitCount());
        }
    }
}
