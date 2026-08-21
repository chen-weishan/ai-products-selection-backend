package com.example.ssds.ai.schema;

import com.example.ssds.ai.model.SceneClassifierOutput;
import com.example.ssds.ai.model.SceneCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SceneClassifierResponseParser {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "sceneType", "confidence", "reasoning", "alternativeScene", "signals");
    private static final Set<String> ALLOWED_ENVELOPES = Set.of("classification", "data", "result");
    private final ObjectMapper objectMapper;

    public SceneClassifierResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SceneClassifierOutput parse(String raw) {
        try {
            JsonNode root = parseRoot(raw);
            if (root == null || !root.isObject()) fail("根節點必須是 object");
            root.fieldNames().forEachRemaining(field -> {
                if (!ALLOWED_FIELDS.contains(field)) fail("不得包含欄位: " + field);
            });
            for (String field : ALLOWED_FIELDS) {
                if (!root.has(field)) fail("缺少欄位: " + field);
            }

            SceneCode scene = parseScene(root.get("sceneType"), false);
            BigDecimal confidence = decimal(root.get("confidence"));
            if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
                fail("confidence 必須介於 0 與 1");
            }
            String reasoning = text(root.get("reasoning"), "reasoning");
            SceneCode alternative = parseScene(root.get("alternativeScene"), true);
            JsonNode signalNodes = root.get("signals");
            if (!signalNodes.isArray() || signalNodes.isEmpty() || signalNodes.size() > 10) {
                fail("signals 必須含 1 至 10 筆");
            }
            List<String> signals = new ArrayList<>();
            signalNodes.forEach(node -> signals.add(text(node, "signals[]")));
            return new SceneClassifierOutput(scene, confidence, reasoning, alternative, signals);
        } catch (AiSchemaValidationException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new AiSchemaValidationException("SceneClassifier 回應不是有效 Schema", exception);
        }
    }

    private JsonNode parseRoot(String raw) throws JsonProcessingException {
        try {
            return unwrapKnownEnvelope(objectMapper.readTree(raw));
        } catch (JsonProcessingException exception) {
            String candidate = extractSceneObject(raw);
            if (candidate == null) throw exception;
            return unwrapKnownEnvelope(objectMapper.readTree(candidate));
        }
    }

    private JsonNode unwrapKnownEnvelope(JsonNode root) {
        if (root == null || !root.isObject() || root.size() != 1) return root;
        for (String envelope : ALLOWED_ENVELOPES) {
            JsonNode candidate = root.get(envelope);
            if (candidate != null && candidate.isObject()) return candidate;
        }
        return root;
    }

    private String extractSceneObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < raw.length(); index++) {
            char value = raw.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
                continue;
            }
            if (value == '"') {
                inString = true;
            } else if (value == '{') {
                if (depth == 0) start = index;
                depth++;
            } else if (value == '}' && depth > 0) {
                depth--;
                if (depth == 0) {
                    String candidate = raw.substring(start, index + 1);
                    if (isSceneObject(candidate)) return candidate;
                    start = -1;
                }
            }
        }
        return null;
    }

    private boolean isSceneObject(String candidate) {
        try {
            JsonNode root = unwrapKnownEnvelope(objectMapper.readTree(candidate));
            return root != null && root.isObject() && root.has("sceneType");
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private static SceneCode parseScene(JsonNode node, boolean nullable) {
        if (nullable && (node == null || node.isNull())) return null;
        if (node == null || !node.isTextual()) fail("情境必須是列舉字串");
        try {
            return SceneCode.parse(node.textValue());
        } catch (IllegalArgumentException exception) {
            throw new AiSchemaValidationException("未知情境列舉: " + node.textValue(), exception);
        }
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || !node.isNumber()) fail("confidence 必須是數字");
        return node.decimalValue();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) fail(field + " 不得空白");
        return node.textValue();
    }

    private static void fail(String message) {
        throw new AiSchemaValidationException(message);
    }
}
