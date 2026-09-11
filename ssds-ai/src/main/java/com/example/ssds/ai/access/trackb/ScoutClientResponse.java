package com.example.ssds.ai.access.trackb;

public record ScoutClientResponse(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        boolean searchedWeb,
        boolean openedWebPage) {}
