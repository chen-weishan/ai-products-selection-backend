package com.example.ssds.ai.model.scene;

import com.example.ssds.ai.model.FallbackReason;

public record SceneClassificationResult(
        SceneClassifierOutput output,
        boolean fallbackApplied,
        FallbackReason fallbackReason,
        boolean cacheHit,
        String rawResponse,
        String model,
        String promptVersion
) {
    public SceneClassificationResult asCacheHit() {
        return new SceneClassificationResult(
                output, fallbackApplied, fallbackReason, true, rawResponse, model, promptVersion);
    }
}
