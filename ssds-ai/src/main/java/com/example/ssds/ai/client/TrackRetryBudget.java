package com.example.ssds.ai.client;

import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Agent 7 使用的 RETRY 池每日請求上限；快取命中不呼叫 acquire。 */
@Component
public class TrackRetryBudget {
    private static final ZoneId ZONE=ZoneId.of("Asia/Taipei");
    private final int dailyLimit; private final AtomicInteger used=new AtomicInteger();
    private volatile LocalDate usageDate=LocalDate.now(ZONE);
    public TrackRetryBudget(@Value("${ai.quota-daily:1000}")int total,@Value("${ai.quota-share-retry:0.1}")double share){dailyLimit=Math.max(0,(int)Math.floor(total*share));}
    public synchronized void acquire(){LocalDate today=LocalDate.now(ZONE);if(!today.equals(usageDate)){usageDate=today;used.set(0);}if(used.get()>=dailyLimit)throw new RetryBudgetExceededException(resetAt());used.incrementAndGet();}
    public OffsetDateTime resetAt(){return usageDate.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();}
}
