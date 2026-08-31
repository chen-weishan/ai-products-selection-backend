package com.example.ssds.ai.schema;

import com.example.ssds.ai.model.*;
import com.example.ssds.core.domain.HeatStage;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class TrendInterpreterResponseParser {
    private static final Set<String> ROOT_FIELDS =
            Set.of("stage", "stageWeeks", "estimatedLifespanDays");
    private final ObjectMapper objectMapper;

    public TrendInterpreterResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TrendInterpreterOutput parse(String raw, TrendInterpreterInput input) {
        try (JsonParser parser = objectMapper.getFactory().createParser(raw)) {
            JsonNode root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) fail("根 JSON 後不得有額外內容");
            requireExactRoot(root);
            HeatStage stage = enumValue(root.get("stage"), HeatStage.class, "stage");
            int stageWeeks = integer(root, "stageWeeks");
            int lifespan = integer(root, "estimatedLifespanDays");
            boolean allowed = input.allowedOutputs().stream().anyMatch(candidate ->
                    candidate.stage() == stage
                            && candidate.stageWeeks() == stageWeeks
                            && candidate.estimatedLifespanDays() == lifespan);
            if (!allowed) fail("輸出必須完整匹配 allowedOutputs 的其中一組");
            return new TrendInterpreterOutput(stage, stageWeeks, lifespan);
        } catch (AiSchemaValidationException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new AiSchemaValidationException("TrendInterpreter 回應不是有效 Schema", exception);
        } catch (IOException exception) {
            throw new AiSchemaValidationException("TrendInterpreter 回應無法讀取", exception);
        }
    }

    private static void requireExactRoot(JsonNode root) {
        if (root == null || !root.isObject()) fail("根節點必須是 object");
        Set<String> actual = new HashSet<>();
        root.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(ROOT_FIELDS)) fail("根物件欄位不符合 TrendInterpreter Schema");
    }

    private static int integer(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            fail(field + " 必須是整數");
        }
        return value.intValue();
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type, String field) {
        if (node == null || !node.isTextual()) fail(field + " 必須是列舉字串");
        try {
            return Enum.valueOf(type, node.textValue());
        } catch (IllegalArgumentException exception) {
            throw new AiSchemaValidationException("未知 " + field + " 列舉", exception);
        }
    }

    private static void fail(String message) {
        throw new AiSchemaValidationException(message);
    }
}
