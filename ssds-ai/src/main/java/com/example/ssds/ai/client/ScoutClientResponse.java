package com.example.ssds.ai.client;

public record ScoutClientResponse(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        boolean searchedWeb,
        boolean openedWebPage) {}
