package com.example.ssds.api.product.service;

/** 每週評分批次建立結果；沒有新項目時 taskId 為 null。 */
public record ProductScoringBatchResult(
        Long taskId,
        int queuedCount,
        int skippedActiveCount
) {
}
