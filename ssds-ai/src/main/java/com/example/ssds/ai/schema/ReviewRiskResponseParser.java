package com.example.ssds.ai.schema;

import com.example.ssds.ai.model.*;
import com.example.ssds.core.domain.ReviewRiskTopic;
import com.example.ssds.core.domain.Sentiment;
import com.example.ssds.core.domain.Severity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ReviewRiskResponseParser {
    private static final Set<String> ROOT_FIELDS = Set.of("reviews", "topicStatistics");
    private static final Set<String> REVIEW_FIELDS = Set.of("reviewId", "sentiment", "riskTopic");
    private static final Set<String> STATISTIC_FIELDS = Set.of("topic", "ratio", "severity");
    private static final BigDecimal RATIO_TOLERANCE = new BigDecimal("0.02");
    private final ObjectMapper objectMapper;

    public ReviewRiskResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ReviewRiskOutput parse(String raw, ReviewRiskInput input) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            requireObject(root, ROOT_FIELDS, "根物件");
            JsonNode reviewNodes = requiredArray(root, "reviews");
            JsonNode statisticNodes = requiredArray(root, "topicStatistics");

            Set<Long> expectedIds = new LinkedHashSet<>();
            input.reviews().forEach(review -> expectedIds.add(review.reviewId()));
            if (expectedIds.size() != input.reviews().size()) fail("輸入 reviewId 不得重複");

            List<ReviewRiskAnalysis> reviews = new ArrayList<>();
            Set<Long> returnedIds = new HashSet<>();
            EnumMap<ReviewRiskTopic, Integer> negativeCounts = new EnumMap<>(ReviewRiskTopic.class);
            for (JsonNode node : reviewNodes) {
                requireObject(node, REVIEW_FIELDS, "reviews[]");
                Long reviewId = integerId(node.get("reviewId"));
                if (!expectedIds.contains(reviewId)) fail("回傳未知 reviewId: " + reviewId);
                if (!returnedIds.add(reviewId)) fail("reviewId 重複: " + reviewId);
                Sentiment sentiment = enumValue(node.get("sentiment"), Sentiment.class, "sentiment");
                ReviewRiskTopic topic = nullableEnum(node.get("riskTopic"), ReviewRiskTopic.class, "riskTopic");
                if (sentiment == Sentiment.NEGATIVE && topic == null) {
                    fail("NEGATIVE 評論必須指定 riskTopic");
                }
                if (sentiment != Sentiment.NEGATIVE && topic != null) {
                    fail("非 NEGATIVE 評論的 riskTopic 必須為 null");
                }
                if (topic != null) negativeCounts.merge(topic, 1, Integer::sum);
                reviews.add(new ReviewRiskAnalysis(reviewId, sentiment, topic));
            }
            if (!returnedIds.equals(expectedIds)) fail("reviews 必須與輸入逐筆一一對應");

            List<ReviewTopicStatistic> statistics = parseStatistics(statisticNodes);
            validateRatios(statistics, negativeCounts);
            return new ReviewRiskOutput(reviews, statistics);
        } catch (AiSchemaValidationException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new AiSchemaValidationException("ReviewRisk 回應不是有效 Schema", exception);
        }
    }

    private static List<ReviewTopicStatistic> parseStatistics(JsonNode nodes) {
        if (nodes.size() != ReviewRiskTopic.values().length) {
            fail("topicStatistics 必須包含五個主題");
        }
        List<ReviewTopicStatistic> statistics = new ArrayList<>();
        EnumSet<ReviewRiskTopic> topics = EnumSet.noneOf(ReviewRiskTopic.class);
        for (JsonNode node : nodes) {
            requireObject(node, STATISTIC_FIELDS, "topicStatistics[]");
            ReviewRiskTopic topic = enumValue(node.get("topic"), ReviewRiskTopic.class, "topic");
            if (!topics.add(topic)) fail("topicStatistics 主題不得重複: " + topic);
            BigDecimal ratio = decimal(node.get("ratio"), "ratio");
            if (ratio.compareTo(BigDecimal.ZERO) < 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
                fail("ratio 必須介於 0 與 1");
            }
            Severity severity = enumValue(node.get("severity"), Severity.class, "severity");
            statistics.add(new ReviewTopicStatistic(topic, ratio, severity));
        }
        if (topics.size() != ReviewRiskTopic.values().length) fail("topicStatistics 缺少主題");
        return statistics;
    }

    private static void validateRatios(
            List<ReviewTopicStatistic> statistics,
            EnumMap<ReviewRiskTopic, Integer> negativeCounts) {
        int totalNegative = negativeCounts.values().stream().mapToInt(Integer::intValue).sum();
        for (ReviewTopicStatistic statistic : statistics) {
            BigDecimal expected = totalNegative == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(negativeCounts.getOrDefault(statistic.topic(), 0))
                            .divide(BigDecimal.valueOf(totalNegative), 6, RoundingMode.HALF_UP);
            if (statistic.ratio().subtract(expected).abs().compareTo(RATIO_TOLERANCE) > 0) {
                fail("topicStatistics ratio 與逐筆分類不一致: " + statistic.topic());
            }
        }
    }

    private static void requireObject(JsonNode node, Set<String> allowed, String label) {
        if (node == null || !node.isObject()) fail(label + " 必須是 object");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(allowed)) fail(label + " 欄位必須且只能是 " + allowed);
    }

    private static JsonNode requiredArray(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) fail(field + " 必須是 array");
        return value;
    }

    private static Long integerId(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || node.longValue() <= 0) {
            fail("reviewId 必須是正整數");
        }
        return node.longValue();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null || !node.isNumber()) fail(field + " 必須是數字");
        return node.decimalValue();
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type, String field) {
        if (node == null || !node.isTextual()) fail(field + " 必須是列舉字串");
        try {
            return Enum.valueOf(type, node.textValue());
        } catch (IllegalArgumentException exception) {
            throw new AiSchemaValidationException("未知 " + field + " 列舉: " + node.textValue(), exception);
        }
    }

    private static <E extends Enum<E>> E nullableEnum(JsonNode node, Class<E> type, String field) {
        if (node == null || node.isNull()) return null;
        return enumValue(node, type, field);
    }

    private static void fail(String message) {
        throw new AiSchemaValidationException(message);
    }
}
