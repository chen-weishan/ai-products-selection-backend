package com.example.ssds.api.product.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 消費評分佇列；任一品項失敗時繼續處理同批其他品項。 */
@Service
public class FullAnalysisTaskExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(FullAnalysisTaskExecutor.class);
    private final FullAnalysisTaskCoordinator coordinator;
    private final FullAnalysisItemProcessor itemProcessor;

    public FullAnalysisTaskExecutor(
            FullAnalysisTaskCoordinator coordinator,
            FullAnalysisItemProcessor itemProcessor
    ) {
        this.coordinator = coordinator;
        this.itemProcessor = itemProcessor;
    }

    public void runPendingTasks() {
        Long taskId;
        while ((taskId = coordinator.claimNext()) != null) {
            for (Long itemId : coordinator.pendingItemIds(taskId)) {
                long started = System.nanoTime();
                try {
                    itemProcessor.process(itemId);
                } catch (Exception error) {
                    LOGGER.warn("FULL_ANALYSIS item failed: taskId={}, itemId={}", taskId, itemId, error);
                    coordinator.markFailed(itemId, error, FullAnalysisItemProcessor.elapsedMillis(started));
                }
            }
            coordinator.finish(taskId);
        }
    }
}
