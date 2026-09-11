package com.example.ssds.ai.model.review;

import com.example.ssds.ai.model.FallbackReason;

public record ReviewRiskResult(
        ReviewRiskOutput output,
        boolean fallbackApplied,
        FallbackReason fallbackReason,
        boolean cacheHit,
        String model,
        String promptVersion
) {
    public ReviewRiskResult asCacheHit() {
        return new ReviewRiskResult(
                output, fallbackApplied, fallbackReason, true, model, promptVersion);
    }
}
