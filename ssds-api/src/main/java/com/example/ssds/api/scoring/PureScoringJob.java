package com.example.ssds.api.scoring;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每週全量純計算評分；與 FULL_ANALYSIS 的 LLM 排程及配額完全獨立。 */
@Component
@ConditionalOnProperty(name = "scoring.schedule-enabled", havingValue = "true")
public class PureScoringJob {
    private static final Logger log = LoggerFactory.getLogger(PureScoringJob.class);
    private final PureScoringBatchService scoring;

    public PureScoringJob(PureScoringBatchService scoring) {
        this.scoring = scoring;
    }

    @Scheduled(cron = "${scoring.schedule-cron:0 50 6 * * MON}", zone = "Asia/Taipei")
    public void scoreAllProducts() {
        PureScoringBatchService.BatchResult result = scoring.evaluateAll(Instant.now());
        log.info(
                "Pure scoring completed: attempted={}, scored={}, insufficient={}, failed={}",
                result.attemptedCount(),
                result.scoredCount(),
                result.insufficientCount(),
                result.failedCount());
    }
}
