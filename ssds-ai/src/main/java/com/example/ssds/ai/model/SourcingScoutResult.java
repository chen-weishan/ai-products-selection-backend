package com.example.ssds.ai.model;

public record SourcingScoutResult(
        SourcingScoutOutput output,
        boolean cacheHit,
        String model,
        String promptVersion,
        Integer promptTokens,
        Integer completionTokens,
        int requestCount) {
    public SourcingScoutResult asCacheHit() {
        return new SourcingScoutResult(output, true, model, promptVersion,
                promptTokens, completionTokens, requestCount);
    }
}
