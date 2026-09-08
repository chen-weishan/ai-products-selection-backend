package com.example.ssds.ai.schema;

import com.example.ssds.ai.model.*;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.InsightRiskType;
import com.example.ssds.core.domain.Severity;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ProductInsightResponseParser {
    private static final Set<String> ROOT_FIELDS = Set.of("sellingPoints", "risks");
    private static final Set<String> SELLING_FIELDS = Set.of("text", "supportCount", "aspect");
    private static final Set<String> RISK_FIELDS = Set.of("text", "type", "severity", "countedInPenalty");
    private static final String INSUFFICIENT_PREFIX = "資料不足";
    private final ObjectMapper objectMapper;

    public ProductInsightResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProductInsightOutput parse(String raw, ProductInsightInput input) {
        try (JsonParser jsonParser = objectMapper.getFactory().createParser(raw)) {
            JsonNode root = objectMapper.readTree(jsonParser);
            if (jsonParser.nextToken() != null) fail("根 JSON 後不得有額外內容");
            requireObject(root, ROOT_FIELDS, "根物件");
            JsonNode sellingNodes = boundedArray(root, "sellingPoints");
            JsonNode riskNodes = boundedArray(root, "risks");

            List<SellingPoint> sellingPoints = new ArrayList<>();
            Set<String> sellingTexts = new HashSet<>();
            for (JsonNode node : sellingNodes) {
                requireObject(node, SELLING_FIELDS, "sellingPoints[]");
                String text = text(node, "text", 200);
                if (!sellingTexts.add(text)) fail("sellingPoints.text 不得重複");
                int supportCount = integer(node, "supportCount");
                if (supportCount < 0 || supportCount > input.reviews().size()) {
                    fail("supportCount 必須介於 0 與輸入評論數量之間");
                }
                if ((supportCount == 0) != text.startsWith(INSUFFICIENT_PREFIX)) {
                    fail("supportCount 為 0 時必須明確標示資料不足，反之亦然");
                }
                sellingPoints.add(new SellingPoint(text, supportCount, text(node, "aspect", 50)));
            }

            EnumSet<InsightRiskType> penalizedTypes = penalizedTypes(input);
            List<ProductInsightRisk> risks = new ArrayList<>();
            Set<String> riskTexts = new HashSet<>();
            for (JsonNode node : riskNodes) {
                requireObject(node, RISK_FIELDS, "risks[]");
                String text = text(node, "text", 200);
                if (!riskTexts.add(text)) fail("risks.text 不得重複");
                InsightRiskType type = enumValue(node.get("type"), InsightRiskType.class, "type");
                Severity severity = enumValue(node.get("severity"), Severity.class, "severity");
                JsonNode countedNode = node.get("countedInPenalty");
                if (countedNode == null || !countedNode.isBoolean()) {
                    fail("countedInPenalty 必須是 boolean");
                }
                boolean counted = countedNode.booleanValue();
                if (counted != penalizedTypes.contains(type)) {
                    fail("countedInPenalty 與後端扣分命中主題不一致: " + type);
                }
                if (text.startsWith(INSUFFICIENT_PREFIX)
                        && (type != InsightRiskType.OTHER || severity != Severity.LOW || counted)) {
                    fail("資料不足的風險必須使用 OTHER、LOW、false");
                }
                risks.add(new ProductInsightRisk(text, type, severity, counted));
            }
            return new ProductInsightOutput(sellingPoints, risks);
        } catch (AiSchemaValidationException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new AiSchemaValidationException("ProductInsight 回應不是有效 Schema", exception);
        } catch (IOException exception) {
            throw new AiSchemaValidationException("ProductInsight 回應無法讀取", exception);
        }
    }

    private static EnumSet<InsightRiskType> penalizedTypes(ProductInsightInput input) {
        EnumSet<InsightRiskType> result = EnumSet.noneOf(InsightRiskType.class);
        for (ProductInsightInput.PenaltyDetail penalty : input.penalties()) {
            if (penalty.penaltyValue() == null || penalty.penaltyValue().signum() <= 0) continue;
            if (penalty.factorCode() == FactorCode.LOGISTICS_RISK) {
                result.add(InsightRiskType.LOGISTICS);
            } else if (penalty.factorCode() == FactorCode.INVENTORY_RISK) {
                result.add(InsightRiskType.INVENTORY);
            } else if (penalty.factorCode() == FactorCode.REVIEW_RISK) {
                for (String topic : penalty.matchedTopics()) {
                    try {
                        result.add(InsightRiskType.valueOf(topic));
                    } catch (IllegalArgumentException ignored) {
                        // matchedTopics 由後端組裝；非 Agent 3 正式列舉不納入 countedInPenalty。
                    }
                }
            }
        }
        return result;
    }

    private static JsonNode boundedArray(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray() || value.size() < 2 || value.size() > 4) {
            fail(field + " 必須是 2 到 4 筆的 array");
        }
        return value;
    }

    private static void requireObject(JsonNode node, Set<String> fields, String label) {
        if (node == null || !node.isObject()) fail(label + " 必須是 object");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) fail(label + " 欄位必須且只能是 " + fields);
    }

    private static String text(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) fail(field + " 必須是字串");
        String text = value.textValue().trim();
        if (text.isEmpty() || text.length() > maxLength) {
            fail(field + " 長度不合法");
        }
        return text;
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) fail(field + " 必須是整數");
        return value.intValue();
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
