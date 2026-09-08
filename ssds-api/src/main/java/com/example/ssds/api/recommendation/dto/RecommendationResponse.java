package com.example.ssds.api.recommendation.dto;

import com.example.ssds.ai.model.RecommendationResult;
import com.example.ssds.core.domain.DecisionType;
import java.time.OffsetDateTime;

public record RecommendationResponse(
        Long productId,
        DecisionType action,
        int qtyMin,
        int qtyMax,
        String quantityText,
        String reasoning,
        boolean fallbackApplied,
        String fallbackReason,
        boolean cacheHit,
        String model,
        String modelAlias,
        String promptVersion,
        int requestCount,
        OffsetDateTime generatedAt) {

    public static RecommendationResponse from(
            Long productId,
            RecommendationResult result,
            OffsetDateTime generatedAt) {
        return new RecommendationResponse(
                productId,
                result.output().action(),
                result.output().qtyMin(),
                result.output().qtyMax(),
                result.output().quantityText(),
                result.output().reasoning(),
                result.fallbackApplied(),
                result.fallbackReason() == null ? null : result.fallbackReason().name(),
                result.cacheHit(),
                result.model(),
                "MODEL_SHORT_GEN",
                result.promptVersion(),
                result.requestCount(),
                generatedAt);
    }
}
