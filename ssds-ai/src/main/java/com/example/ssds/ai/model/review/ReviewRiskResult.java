package com.example.ssds.ai.model.review;

import com.example.ssds.ai.model.FallbackReason;

public record ReviewRiskResult(
        ReviewRiskOutput output,
        boolean fallbackApplied,
        FallbackReason fallbackReason,
        boolean cacheHit,
        String model,
        String promptVersion,
        Integer promptTokens,
        Integer completionTokens,
        int requestCount
) {
    public ReviewRiskResult asCacheHit() {
        return new ReviewRiskResult(
                output,
                fallbackApplied,
                fallbackReason,
                true,
                model,
                promptVersion,
                null,
                null,
                0);
    }
}
