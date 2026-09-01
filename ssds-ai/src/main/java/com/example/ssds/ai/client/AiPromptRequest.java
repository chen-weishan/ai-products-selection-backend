package com.example.ssds.ai.client;

import com.example.ssds.core.domain.AiTaskType;
import com.fasterxml.jackson.databind.JsonNode;

public record AiPromptRequest(
        AiTaskType taskType,
        String model,
        String systemPrompt,
        String userPrompt,
        JsonNode responseSchema,
        boolean retryAttempt
) {
    public AiPromptRequest(
            AiTaskType taskType,
            String model,
            String systemPrompt,
            String userPrompt,
            JsonNode responseSchema) {
        this(taskType, model, systemPrompt, userPrompt, responseSchema, false);
    }
}
