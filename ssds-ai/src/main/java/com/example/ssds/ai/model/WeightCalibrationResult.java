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
        int requestCount) {}
