package com.example.ssds.ai.model;

public record TrendInterpreterResult(
        TrendInterpreterOutput output,
        boolean fallbackApplied,
        FallbackReason fallbackReason,
        boolean cacheHit,
        String model,
        String promptVersion,
        Integer promptTokens,
        Integer completionTokens,
        int requestCount) {

    public TrendInterpreterResult asCacheHit() {
        return new TrendInterpreterResult(
                output, fallbackApplied, fallbackReason, true, model, promptVersion,
                null, null, 0);
    }
}
