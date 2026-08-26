package com.example.ssds.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class ProductInsightSchema {
    private ProductInsightSchema() {}

    public static JsonNode create(ObjectMapper mapper) {
        ObjectNode sellingProperties = mapper.createObjectNode();
        sellingProperties.set("text", string(mapper, 1, 200));
        sellingProperties.set("supportCount", mapper.createObjectNode()
                .put("type", "integer").put("minimum", 0).put("maximum", 200));
        sellingProperties.set("aspect", string(mapper, 1, 50));

        ObjectNode riskProperties = mapper.createObjectNode();
        riskProperties.set("text", string(mapper, 1, 200));
        riskProperties.set("type", enumNode(
                mapper, "QUALITY", "FOOD_SAFETY", "SHIPPING_DAMAGE", "PRICE", "LOGISTICS", "INVENTORY", "OTHER"));
        riskProperties.set("severity", enumNode(mapper, "LOW", "MEDIUM", "HIGH"));
        riskProperties.set("countedInPenalty", mapper.createObjectNode().put("type", "boolean"));

        ObjectNode properties = mapper.createObjectNode();
        properties.set("sellingPoints", array(
                mapper,
                object(mapper, sellingProperties, "text", "supportCount", "aspect"),
                2,
                4));
        properties.set("risks", array(
                mapper,
                object(mapper, riskProperties, "text", "type", "severity", "countedInPenalty"),
                2,
                4));
        return object(mapper, properties, "sellingPoints", "risks");
    }

    private static ObjectNode array(ObjectMapper mapper, ObjectNode items, int min, int max) {
        ObjectNode node = mapper.createObjectNode()
                .put("type", "array").put("minItems", min).put("maxItems", max);
        node.set("items", items);
        return node;
    }

    private static ObjectNode string(ObjectMapper mapper, int min, int max) {
        return mapper.createObjectNode()
                .put("type", "string").put("minLength", min).put("maxLength", max);
    }

    private static ObjectNode object(
            ObjectMapper mapper, ObjectNode properties, String... requiredFields) {
        ObjectNode node = mapper.createObjectNode().put("type", "object");
        node.set("properties", properties);
        var required = mapper.createArrayNode();
        for (String field : requiredFields) required.add(field);
        node.set("required", required);
        node.put("additionalProperties", false);
        return node;
    }

    private static ObjectNode enumNode(ObjectMapper mapper, String... values) {
        ObjectNode node = mapper.createObjectNode().put("type", "string");
        var allowed = mapper.createArrayNode();
        for (String value : values) allowed.add(value);
        node.set("enum", allowed);
        return node;
    }
}
