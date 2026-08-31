package com.example.ssds.ai.client;

import com.fasterxml.jackson.databind.JsonNode;

/** 驗證 Mistral 本次回答確實先執行了本請求唯一允許的搜尋 Connector。 */
public final class SourcingToolEvidenceVerifier {
    private SourcingToolEvidenceVerifier() {}
    public static JsonNode verifiedMessageOutput(JsonNode response, String connector) {
        boolean executedTool = false;
        JsonNode message = null;
        JsonNode outputs = response.path("outputs");
        if (!outputs.isArray()) throw new IllegalStateException("Mistral 回應缺少 outputs");
        for (JsonNode output : outputs) {
            String type = output.path("type").asText();
            if ("tool.execution".equals(type)) executedTool = true;
            if ("message.output".equals(type)) message = output;
        }
        if (!executedTool)
            throw new ScoutToolEvidenceException("Mistral 回應缺少搜尋 Connector 執行證據: " + connector);
        if (message == null) throw new IllegalStateException("Mistral 回應缺少 message.output");
        return message;
    }
}
