package com.example.ssds.api.insight.dto;

import com.example.ssds.ai.model.insight.ProductInsightResult;
import com.example.ssds.ai.model.insight.ProductInsightRisk;
import com.example.ssds.ai.model.insight.ProductInsightOutput;
import com.example.ssds.ai.model.insight.SellingPoint;
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
        boolean completed = !result.fallbackApplied() && result.output().hasSufficientEvidence();
        String message = result.fallbackApplied()
                ? "賣點與風險分析未完成"
                : completed
                        ? null
                        : sourceReviewCount == 0
                                ? "賣點與風險分析未執行：無評論資料"
                                : insufficientEvidenceMessage(result.output());
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

    private static String insufficientEvidenceMessage(ProductInsightOutput output) {
        boolean sellingPointInsufficient = output.supportedSellingPointCount() < 2;
        boolean riskInsufficient = output.supportedRiskCount() < 2;
        if (sellingPointInsufficient && riskInsufficient) {
            return "賣點證據不足／風險證據不足";
        }
        return sellingPointInsufficient ? "賣點證據不足" : "風險證據不足";
    }
}
