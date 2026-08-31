package com.example.ssds.ai.model;

public record WeightCalibrationResult(
        WeightCalibrationOutput output,
        boolean fallbackApplied,
        FallbackReason fallbackReason,
        boolean cacheHit,
        String model,
        String promptVersion,
        Integer promptTokens,
        Integer completionTokens,
        int requestCount) {
    public WeightCalibrationResult asCacheHit() {
        return new WeightCalibrationResult(output, fallbackApplied, fallbackReason, true, model,
                promptVersion, promptTokens, completionTokens, requestCount);
    }
}
