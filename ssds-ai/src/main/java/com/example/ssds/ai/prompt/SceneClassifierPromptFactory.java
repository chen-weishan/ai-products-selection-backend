package com.example.ssds.ai.prompt;

import com.example.ssds.ai.model.SceneClassifierInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class SceneClassifierPromptFactory {
    public static final String PROMPT_VERSION = "scene-v3";
    private final ObjectMapper objectMapper;

    public SceneClassifierPromptFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String systemPrompt() {
        return """
                你是零售團購採購決策輔助系統的情境原型分類器。
                請將 INPUT_JSON 中的單一品項分類為 VIRAL_TOPIC、FESTIVAL、REPLENISHMENT、SEASONAL 之一。

                判定原則：
                - VIRAL_TOPIC：短期熱度明顯上升，且不是由明確節慶或季節性主導。
                - FESTIVAL：輸入中的 festivalMatches 有明確節慶匹配訊號。
                - SEASONAL：輸入中的季節與熱度資料呈現明確季節性。
                - REPLENISHMENT：需求與歷史開團較穩定，或資料不足以支持其他情境。

                輸出規則：
                - 只能輸出一個合法 JSON object，不得輸出 Markdown code block、前言、結尾或來源說明。
                - 根物件必須且只能包含 sceneType、confidence、reasoning、alternativeScene、signals 五個欄位。
                - sceneType 必須是上述四個列舉值之一。
                - confidence 必須是 0 到 1 的 JSON number，不得使用字串。
                - reasoning 必須使用繁體中文，並引用 INPUT_JSON 中的實際資料。
                - alternativeScene 沒有合理備選時仍須輸出 null，不得省略。
                - signals 必須包含 1 至 10 個非空字串，每一項都要使用「輸入欄位: 輸入值」格式。

                限制條款：
                - 只能根據 INPUT_JSON 作答，不得使用外部知識，不得搜尋網路或呼叫工具。
                - 資料不足時必須明確說明不足，不得推測；此時優先選 REPLENISHMENT 並降低 confidence。
                - 除 confidence 外，不得產生未在 INPUT_JSON 出現的具體數字。
                - 不得對品牌或供應商作出評價性斷言。
                - 不得輸出或建議任何權重數值，不得加入 weights 欄位。
                - 不得使用 classification、data、result 或其他包裝欄位。
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
