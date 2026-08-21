package com.example.ssds.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class ReviewRiskSchema {
    private ReviewRiskSchema() {}

    public static JsonNode create(ObjectMapper objectMapper) {
        ObjectNode reviewProperties = objectMapper.createObjectNode();
        reviewProperties.set("reviewId", objectMapper.createObjectNode()
                .put("type", "integer").put("minimum", 1));
        reviewProperties.set("sentiment", enumNode(
                objectMapper, "POSITIVE", "NEUTRAL", "NEGATIVE"));
        reviewProperties.set("riskTopic", objectMapper.createObjectNode()
                .set("anyOf", objectMapper.createArrayNode()
                        .add(enumNode(objectMapper, "QUALITY", "FOOD_SAFETY", "SHIPPING_DAMAGE", "PRICE", "OTHER"))
                        .add(objectMapper.createObjectNode().put("type", "null"))));
        ObjectNode reviewItem = objectSchema(objectMapper, reviewProperties, "reviewId", "sentiment", "riskTopic");

        ObjectNode statisticProperties = objectMapper.createObjectNode();
        statisticProperties.set("topic", enumNode(
                objectMapper, "QUALITY", "FOOD_SAFETY", "SHIPPING_DAMAGE", "PRICE", "OTHER"));
        statisticProperties.set("ratio", objectMapper.createObjectNode()
                .put("type", "number").put("minimum", 0).put("maximum", 1));
        statisticProperties.set("severity", enumNode(objectMapper, "LOW", "MEDIUM", "HIGH"));
        ObjectNode statisticItem = objectSchema(
                objectMapper, statisticProperties, "topic", "ratio", "severity");

        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode reviews = objectMapper.createObjectNode().put("type", "array").put("maxItems", 200);
        reviews.set("items", reviewItem);
        properties.set("reviews", reviews);
        ObjectNode statistics = objectMapper.createObjectNode()
                .put("type", "array").put("minItems", 5).put("maxItems", 5);
        statistics.set("items", statisticItem);
        properties.set("topicStatistics", statistics);
        return objectSchema(objectMapper, properties, "reviews", "topicStatistics");
    }

    private static ObjectNode objectSchema(
            ObjectMapper objectMapper, ObjectNode properties, String... requiredFields) {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        var required = objectMapper.createArrayNode();
        for (String field : requiredFields) required.add(field);
        schema.set("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode enumNode(ObjectMapper objectMapper, String... values) {
        ObjectNode node = objectMapper.createObjectNode().put("type", "string");
        var allowed = objectMapper.createArrayNode();
        for (String value : values) allowed.add(value);
        node.set("enum", allowed);
        return node;
    }
}
