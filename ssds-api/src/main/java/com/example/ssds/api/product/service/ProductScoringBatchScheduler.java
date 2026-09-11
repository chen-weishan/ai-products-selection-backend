package com.example.ssds.api.product.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每週一台北時間 07:00 建立全量評分任務。 */
@Component
public class ProductScoringBatchScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProductScoringBatchScheduler.class);

    private final ProductScoringBatchService scoringBatchService;

    public ProductScoringBatchScheduler(ProductScoringBatchService scoringBatchService) {
        this.scoringBatchService = scoringBatchService;
    }

    @Scheduled(
            cron = "${ssds.scoring.weekly-cron:0 0 7 * * MON}",
            zone = "Asia/Taipei"
    )
    public void enqueueWeeklyBatch() {
        ProductScoringBatchResult result = scoringBatchService.enqueueWeeklyBatch();
        LOGGER.info(
                "Weekly scoring batch created: taskId={}, queued={}, skippedActive={}",
                result.taskId(), result.queuedCount(), result.skippedActiveCount()
        );
    }
}
