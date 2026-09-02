package com.example.ssds.api.product.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 開發與單節點環境的 FULL_ANALYSIS 佇列消費器。 */
@Component
public class FullAnalysisTaskPoller {

    private final FullAnalysisTaskExecutor executor;

    public FullAnalysisTaskPoller(FullAnalysisTaskExecutor executor) {
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${ssds.scoring.poll-delay-ms:5000}")
    public void poll() {
        executor.runPendingTasks();
    }
}
