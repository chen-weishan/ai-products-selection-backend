package com.example.ssds.api.product.dto;

import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import java.util.Set;

/** 批次完整分析任務建立結果。 */
public record ProductBatchAnalyzeResponse(
        Long taskId,
        AiTaskType taskType,
        TaskStatus status,
        int queuedCount,
        Set<Long> productIds
) {
    public ProductBatchAnalyzeResponse {
        productIds = Set.copyOf(productIds);
    }
}
