package com.example.ssds.ai.schema;

import com.example.ssds.ai.model.RecommendationInput;
import com.example.ssds.ai.model.RecommendationOutput;
import com.example.ssds.core.domain.DecisionType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RecommendationResponseParser {
    private static final Set<String> ROOT_FIELDS =
            Set.of("action", "qtyMin", "qtyMax", "quantityText", "reasoning");
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private final ObjectMapper objectMapper;

    public RecommendationResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RecommendationOutput parse(String raw, RecommendationInput input) {
        try (JsonParser parser = objectMapper.getFactory().createParser(raw)) {
            JsonNode root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) fail("根 JSON 後不得有額外內容");
            requireExactRoot(root);
            DecisionType action = enumValue(root.get("action"), DecisionType.class, "action");
            int qtyMin = integer(root, "qtyMin");
            int qtyMax = integer(root, "qtyMax");
            if (qtyMin > qtyMax) fail("qtyMin 不得大於 qtyMax");
            if (!input.allowedQuantities().contains(qtyMin)
                    || !input.allowedQuantities().contains(qtyMax)) {
                fail("qtyMin 與 qtyMax 必須來自 allowedQuantities");
            }
            if (action == DecisionType.REJECT && (qtyMin != 0 || qtyMax != 0)) {
                fail("REJECT 的 qtyMin 與 qtyMax 必須都是 0");
            }
            String quantityText = text(root, "quantityText", 100);
            String reasoning = text(root, "reasoning", 500);
            validateQuantityText(quantityText, qtyMin, qtyMax);
            validateNumericWhitelist(quantityText, reasoning, input);
            return new RecommendationOutput(action, qtyMin, qtyMax, quantityText, reasoning);
        } catch (AiSchemaValidationException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new AiSchemaValidationException("Recommendation 回應不是有效 Schema", exception);
        } catch (IOException exception) {
            throw new AiSchemaValidationException("Recommendation 回應無法讀取", exception);
        }
    }

    private static void validateQuantityText(String text, int qtyMin, int qtyMax) {
        if (qtyMin == 0 && qtyMax == 0) return;
        Set<Integer> mentioned = new HashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            try {
                mentioned.add(new BigDecimal(matcher.group()).intValueExact());
            } catch (ArithmeticException exception) {
                fail("quantityText 的數量必須是整數");
            }
        }
        if (!mentioned.contains(qtyMin) || !mentioned.contains(qtyMax)) {
            fail("quantityText 必須包含 qtyMin 與 qtyMax");
        }
    }

    private static void validateNumericWhitelist(
            String quantityText, String reasoning, RecommendationInput input) {
        Set<BigDecimal> allowed = new HashSet<>();
        input.factors().stream()
                .map(RecommendationInput.FactorPercentile::percentile)
                .filter(Objects::nonNull)
                .map(RecommendationResponseParser::normalize)
                .forEach(allowed::add);
        if (input.bonusSubtotal() != null) allowed.add(normalize(input.bonusSubtotal()));
        if (input.penaltySubtotal() != null) allowed.add(normalize(input.penaltySubtotal()));
        if (input.festival() != null) {
            allowed.add(normalize(BigDecimal.valueOf(input.festival().daysRemaining())));
        }
        input.allowedQuantities().stream()
                .map(BigDecimal::valueOf)
                .map(RecommendationResponseParser::normalize)
                .forEach(allowed::add);

        Matcher matcher = NUMBER.matcher(quantityText + "\n" + reasoning);
        while (matcher.find()) {
            BigDecimal outputNumber;
            try {
                outputNumber = normalize(new BigDecimal(matcher.group()));
            } catch (NumberFormatException exception) {
                fail("輸出含無法辨識的數字");
                return;
            }
            if (!allowed.contains(outputNumber)) {
                fail("輸出數字未出現在輸入白名單: " + matcher.group());
            }
        }
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.stripTrailingZeros();
    }

    private static void requireExactRoot(JsonNode root) {
        if (root == null || !root.isObject()) fail("根節點必須是 object");
        Set<String> actual = new HashSet<>();
        root.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(ROOT_FIELDS)) fail("根物件欄位必須且只能是 " + ROOT_FIELDS);
    }

    private static int integer(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            fail(field + " 必須是整數");
        }
        return value.intValue();
    }

    private static String text(JsonNode root, String field, int maxLength) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) fail(field + " 必須是字串");
        String text = value.textValue().trim();
        if (text.isEmpty() || text.length() > maxLength) fail(field + " 長度不合法");
        return text;
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type, String field) {
        if (node == null || !node.isTextual()) fail(field + " 必須是列舉字串");
        try {
            return Enum.valueOf(type, node.textValue());
        } catch (IllegalArgumentException exception) {
            throw new AiSchemaValidationException("未知 " + field + " 列舉: " + node.textValue(), exception);
        }
    }

    private static void fail(String message) {
        throw new AiSchemaValidationException(message);
    }
}
