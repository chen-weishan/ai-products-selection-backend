package com.example.ssds.ai.config;

import java.math.BigDecimal;

/** Receives the non-secret S-14 AI settings after a successful transactional update. */
public interface AiRuntimeConfigurable {
    void reconfigure(
            MistralModelCatalog catalog,
            int retryMax,
            int cacheDays,
            int trendCacheDays,
            int sourcingCacheDays,
            int batchItemCap,
            int timeoutSeconds,
            int sourcingTimeoutSeconds);

    default void reconfigureSceneAdoptConfidence(BigDecimal minimumConfidence) {
        // Only the scene classifier consumes this P1 threshold.
    }
}
