package com.example.ssds.api.insight.dto;

import com.example.ssds.ai.model.ProductInsightResult;
import com.example.ssds.ai.model.ProductInsightRisk;
import com.example.ssds.ai.model.SellingPoint;
import java.time.OffsetDateTime;
import java.util.List;

public record ProductInsightResponse(
        Long productId,
        List<SellingPoint> sellingPoints,
        List<ProductInsightRisk> risks,
        boolean analysisCompleted,
        String statusMessage,
        boolean fallbackApplied,
        String fallbackReason,
        boolean cacheHit,
        String model,
        String modelAlias,
        String promptVersion,
        int sourceReviewCount,
        int requestCount,
        OffsetDateTime generatedAt) {

    public static ProductInsightResponse from(
            Long productId,
            int sourceReviewCount,
            ProductInsightResult result,
            OffsetDateTime generatedAt) {
        boolean completed = !result.fallbackApplied() && !result.output().sellingPoints().isEmpty();
        String message = result.fallbackApplied()
                ? "賣點與風險分析未完成"
                : completed ? null : "評論資料不足";
        return new ProductInsightResponse(
                productId,
                result.output().sellingPoints(),
                result.output().risks(),
                completed,
                message,
                result.fallbackApplied(),
                result.fallbackReason() == null ? null : result.fallbackReason().name(),
                result.cacheHit(),
                result.model(),
                "MODEL_LONG_TEXT",
                result.promptVersion(),
                sourceReviewCount,
                result.requestCount(),
                generatedAt);
    }
}
