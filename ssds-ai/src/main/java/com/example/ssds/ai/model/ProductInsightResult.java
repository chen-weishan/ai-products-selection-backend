package com.example.ssds.ai.model;

public record ProductInsightResult(
        ProductInsightOutput output,
        boolean fallbackApplied,
        FallbackReason fallbackReason,
        boolean cacheHit,
        String model,
        String promptVersion,
        Integer promptTokens,
        Integer completionTokens,
        int requestCount) {

    public ProductInsightResult asCacheHit() {
        return new ProductInsightResult(
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
