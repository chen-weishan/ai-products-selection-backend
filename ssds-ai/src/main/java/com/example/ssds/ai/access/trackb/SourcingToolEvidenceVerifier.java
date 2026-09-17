package com.example.ssds.ai.access.trackb;

import com.fasterxml.jackson.databind.JsonNode;

/** 驗證 Mistral 本次回答確實先執行了本請求唯一允許的搜尋 Connector。 */
public final class SourcingToolEvidenceVerifier {
    private SourcingToolEvidenceVerifier() {}

    /** 保留原入口供既有呼叫者使用。 */
    public static JsonNode verifiedMessageOutput(JsonNode response, String connector) {
        return verify(response, connector).messageOutput();
    }

    public static Verification verify(JsonNode response, String connector) {
        JsonNode outputs = response.path("outputs");
        if (!outputs.isArray()) throw new IllegalStateException("Mistral 回應缺少 outputs");

        boolean executedTool = false;
        boolean openedWebPage = false;
        int lastSuccessfulToolIndex = -1;
        int lastMessageIndex = -1;
        JsonNode message = null;
        for (int index = 0; index < outputs.size(); index++) {
            JsonNode output = outputs.get(index);
            String type = output.path("type").asText();
            if ("tool.execution".equals(type)) {
                verifyConnectorIdentity(output, connector);
                if (!isSuccessful(output)) continue;
                String toolName = toolName(output);
                if (toolName.isBlank()) {
                    throw new ScoutToolEvidenceException(
                            "Mistral 搜尋工具執行證據缺少工具名稱: " + connector);
                }
                executedTool = true;
                openedWebPage |= "open_url".equals(toolName) || "open-url".equals(toolName);
                lastSuccessfulToolIndex = index;
            }
            if ("message.output".equals(type)) {
                message = output;
                lastMessageIndex = index;
            }
        }
        if (!executedTool)
            throw new ScoutToolEvidenceException(
                    "Mistral 回應缺少成功的搜尋 Connector 執行證據: " + connector);
        if (message == null) throw new IllegalStateException("Mistral 回應缺少 message.output");
        if (lastMessageIndex <= lastSuccessfulToolIndex) {
            throw new ScoutToolEvidenceException(
                    "Mistral 最終輸出早於搜尋 Connector 執行完成: " + connector);
        }
        return new Verification(message, true, openedWebPage);
    }

    private static void verifyConnectorIdentity(JsonNode output, String connector) {
        for (String field : new String[] {"connector_id", "connectorId", "connector"}) {
            JsonNode value = output.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()
                    && !connector.equals(value.asText())) {
                throw new ScoutToolEvidenceException(
                        "Mistral 回應包含非本次允許的 Connector 執行證據: " + value.asText());
            }
        }
    }

    private static boolean isSuccessful(JsonNode output) {
        JsonNode error = output.get("error");
        if (error != null && !error.isNull()
                && (!error.isTextual() || !error.asText().isBlank())) return false;
        String status = output.path("status").asText("").toLowerCase(java.util.Locale.ROOT);
        return !java.util.Set.of("failed", "error", "cancelled", "canceled").contains(status);
    }

    private static String toolName(JsonNode output) {
        String function = output.path("function").asText("").strip()
                .toLowerCase(java.util.Locale.ROOT);
        if (!function.isBlank()) return function;
        return output.path("name").asText("").strip().toLowerCase(java.util.Locale.ROOT);
    }

    public record Verification(JsonNode messageOutput, boolean searchedWeb, boolean openedWebPage) {}
}
