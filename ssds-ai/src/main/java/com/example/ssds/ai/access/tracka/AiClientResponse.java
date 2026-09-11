package com.example.ssds.ai.access.tracka;

public record AiClientResponse(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens
) {}
