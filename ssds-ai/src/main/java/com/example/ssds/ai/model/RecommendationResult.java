package com.example.ssds.ai.model;

public record RecommendationResult(
        RecommendationOutput output,
        boolean fallbackApplied,
        FallbackReason fallbackReason,
        boolean cacheHit,
        String model,
        String promptVersion,
        Integer promptTokens,
        Integer completionTokens,
        int requestCount) {

    public RecommendationResult asCacheHit() {
        return new RecommendationResult(
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
