package com.example.ssds.ai.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 外部 LLM 的集中式啟用開關與結構化資料 fail-closed 防線。 */
@Component
public class ExternalLlmPolicy {
    private static final String INPUT_MARKER = "INPUT_JSON:\n";
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "productid", "reviewid", "keywordid", "categoryid",
            "supplier", "supplierid", "suppliername", "suppliercontact", "supplierphone", "quote",
            "cost", "price", "suggestedprice", "margin", "marginrate", "actualsales", "realizedmargin",
            "memberprofile", "customerpriceband");

    private final boolean enabled;
    private final ObjectMapper objectMapper;

    public ExternalLlmPolicy(
            @Value("${ai.external-llm-enabled:true}") boolean enabled,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.objectMapper = objectMapper;
    }

    public void requireEnabled() {
        if (!enabled) throw new ExternalLlmDisabledException("外部 AI 已依政策停用");
    }

    public void validateUserJson(String json) {
        requireEnabled();
        try {
            inspect(objectMapper.readTree(json), "INPUT_JSON");
        } catch (JsonProcessingException exception) {
            throw new OutboundDataPolicyException("外送 INPUT_JSON 不是合法 JSON", exception);
        }
    }

    public void validateCombinedPrompt(String prompt) {
        requireEnabled();
        int marker = prompt == null ? -1 : prompt.lastIndexOf(INPUT_MARKER);
        if (marker < 0) throw new OutboundDataPolicyException("外送 Prompt 缺少 INPUT_JSON");
        validateUserJson(prompt.substring(marker + INPUT_MARKER.length()).trim());
    }

    private static void inspect(JsonNode node, String path) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalized = field.getKey().replace("_", "").replace("-", "")
                        .toLowerCase(Locale.ROOT);
                if (FORBIDDEN_KEYS.contains(normalized)) {
                    throw new OutboundDataPolicyException("外送 payload 含未核准欄位: " + path + "." + field.getKey());
                }
                inspect(field.getValue(), path + "." + field.getKey());
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) inspect(node.get(index), path + "[" + index + "]");
        }
    }
}
