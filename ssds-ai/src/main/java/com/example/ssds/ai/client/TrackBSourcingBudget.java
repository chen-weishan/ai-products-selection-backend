package com.example.ssds.ai.client;

import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Agent 6 的隔離每日配額；不會消耗或阻斷 A 軌池。 */
@Component
public class TrackBSourcingBudget {
    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private final int dailyLimit;
    private final AtomicInteger used = new AtomicInteger();
    private volatile LocalDate usageDate = LocalDate.now(ZONE);

    public TrackBSourcingBudget(
            @Value("${ai.quota-daily:1000}") int dailyQuota,
            @Value("${ai.quota-share-track-b:0.2}") double share) {
        this.dailyLimit = Math.max(0, (int) Math.floor(dailyQuota * share));
    }

    public synchronized void acquire() {
        LocalDate today = LocalDate.now(ZONE);
        if (!today.equals(usageDate)) { usageDate = today; used.set(0); }
        if (used.get() >= dailyLimit) throw new SourcingBudgetExceededException(resetAt());
        used.incrementAndGet();
    }

    public OffsetDateTime resetAt() {
        return usageDate.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
    }
}
