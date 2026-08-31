package com.example.ssds.ai.schema;

import com.example.ssds.ai.model.SourcingScoutOutput;
import com.example.ssds.core.domain.HeatStage;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SourcingScoutResponseParser {
    private static final Set<String> FIELDS = Set.of(
            "report", "opportunitySignals", "riskSignals", "heatStage");
    private final ObjectMapper mapper;
    public SourcingScoutResponseParser(ObjectMapper mapper) { this.mapper = mapper; }

    public SourcingScoutOutput parse(String raw) {
        try {
            JsonNode root = parseRoot(raw);
            if (root == null || !root.isObject()) fail("根節點必須是 object");
            Set<String> actual = new HashSet<>();
            root.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(FIELDS)) fail("根物件欄位不符合 SourcingScout Schema: " + actual);
            String report = report(root);
            List<String> opportunities = strings(root, "opportunitySignals");
            List<String> risks = strings(root, "riskSignals");
            HeatStage stage;
            try { stage = HeatStage.valueOf(text(root, "heatStage")); }
            catch (IllegalArgumentException exception) {
                throw new AiSchemaValidationException("未知 heatStage 列舉", exception);
            }
            return new SourcingScoutOutput(report, opportunities, risks, stage);
        } catch (AiSchemaValidationException exception) { throw exception; }
        catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidSchema(raw, exception);
        }
    }

    /**
     * 先要求完整 content 是單一 JSON；只有語法或包裝文字造成解析失敗時，
     * 才擷取唯一一個欄位完全符合 Agent 6 的平衡 JSON object。
     */
    private JsonNode parseRoot(String raw) throws JsonProcessingException {
        try {
            return readSingleRoot(raw);
        } catch (JsonProcessingException | AiSchemaValidationException firstFailure) {
            String candidate = extractUniqueScoutObject(raw);
            if (candidate == null) {
                if (firstFailure instanceof JsonProcessingException jsonFailure) throw jsonFailure;
                throw firstFailure;
            }
            return readSingleRoot(candidate);
        }
    }

    private JsonNode readSingleRoot(String raw) throws JsonProcessingException {
        try (JsonParser parser = mapper.getFactory().createParser(raw)) {
            JsonNode root = mapper.readTree(parser);
            if (parser.nextToken() != null) fail("根 JSON 後不得有額外內容");
            return root;
        } catch (JsonProcessingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AiSchemaValidationException("SourcingScout 回應無法讀取", exception);
        }
    }

    private String extractUniqueScoutObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String matched = null;
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < raw.length(); index++) {
            char value = raw.charAt(index);
            if (inString) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') inString = false;
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
                    if (hasExactScoutFields(candidate)) {
                        if (matched != null) fail("回應包含多個 SourcingScout JSON 物件");
                        matched = candidate;
                    }
                    start = -1;
                }
            }
        }
        return matched;
    }

    private boolean hasExactScoutFields(String candidate) {
        try {
            JsonNode root = mapper.readTree(candidate);
            if (root == null || !root.isObject()) return false;
            Set<String> actual = new HashSet<>();
            root.fieldNames().forEachRemaining(actual::add);
            return actual.equals(FIELDS);
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private static AiSchemaValidationException invalidSchema(String raw, Exception cause) {
        int length = raw == null ? 0 : raw.length();
        boolean codeFence = raw != null && raw.contains("```");
        String location = "";
        if (cause instanceof JsonProcessingException json && json.getLocation() != null) {
            location = ", line=" + json.getLocation().getLineNr()
                    + ", column=" + json.getLocation().getColumnNr();
        }
        return new AiSchemaValidationException(
                "SourcingScout 回應不是有效 Schema (length=" + length
                        + ", codeFence=" + codeFence + location + ")",
                cause);
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) fail(field + " 必須是非空字串");
        return value.textValue().trim();
    }
    private static String report(JsonNode root) {
        String value = text(root, "report");
        if (value.length() < 20 || value.length() > 3000) fail("report 長度必須介於 20 與 3000 個字元");
        return value;
    }
    private static List<String> strings(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray() || value.size() < 1 || value.size() > 5) fail(field + " 必須有 1 至 5 條");
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual() || item.textValue().isBlank()) fail(field + " 只能包含非空字串");
            result.add(item.textValue().trim());
        });
        return List.copyOf(result);
    }
    private static void fail(String message) { throw new AiSchemaValidationException(message); }
}
