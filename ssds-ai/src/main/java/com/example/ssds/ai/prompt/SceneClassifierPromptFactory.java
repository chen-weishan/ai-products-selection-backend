package com.example.ssds.ai.prompt;

import com.example.ssds.ai.model.SceneClassifierInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class SceneClassifierPromptFactory {
    public static final String PROMPT_VERSION = "scene-v1";
    private final ObjectMapper objectMapper;

    public SceneClassifierPromptFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String systemPrompt() {
        return """
                你是商品情境分類器。只能選 VIRAL_TOPIC、FESTIVAL、REPLENISHMENT、SEASONAL 之一。
                不得輸出或建議任何權重數值。只根據提供的資料判斷，不得猜測缺失資料。
                reasoning 使用繁體中文；signals 必須指出實際輸入訊號。
                """;
    }

    public String userPrompt(SceneClassifierInput input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("無法序列化 SceneClassifier 輸入", exception);
        }
    }
}
