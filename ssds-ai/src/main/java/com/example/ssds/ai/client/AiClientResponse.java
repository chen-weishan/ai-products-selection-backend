package com.example.ssds.ai.client;

public record AiClientResponse(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens
) {}
