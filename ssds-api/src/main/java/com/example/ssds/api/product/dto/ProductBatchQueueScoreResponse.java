package com.example.ssds.api.product.dto;

import com.example.ssds.core.domain.TaskStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * FR-03-1 批次加入評分佇列的結果。
 *
 * <p>此操作採部分成功：不存在、不符合評分條件或已在佇列中的品項會被略過，
 * 其餘品項仍會建立評分任務。
 */
public record ProductBatchQueueScoreResponse(
        Long taskId,
        TaskStatus status,
        int requestedCount,
        int queuedCount,
        Set<Long> queuedProductIds,
        Set<Long> missingProductIds,
        Set<Long> ineligibleProductIds,
        Set<Long> alreadyQueuedProductIds,
        List<String> warnings
) {
    public ProductBatchQueueScoreResponse {
        queuedProductIds = immutableSet(queuedProductIds);
        missingProductIds = immutableSet(missingProductIds);
        ineligibleProductIds = immutableSet(ineligibleProductIds);
        alreadyQueuedProductIds = immutableSet(alreadyQueuedProductIds);
        warnings = List.copyOf(warnings);
    }

    private static <T> Set<T> immutableSet(Set<T> values) {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
