package com.example.ssds.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class SceneClassifierSchema {
    private SceneClassifierSchema() {}

    public static JsonNode create(ObjectMapper objectMapper) {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("sceneType", enumNode(objectMapper));
        properties.set("confidence", objectMapper.createObjectNode()
                .put("type", "number").put("minimum", 0).put("maximum", 1));
        properties.set("reasoning", objectMapper.createObjectNode()
                .put("type", "string").put("minLength", 1));
        properties.set("alternativeScene", objectMapper.createObjectNode()
                .set("anyOf", objectMapper.createArrayNode()
                        .add(enumNode(objectMapper))
                        .add(objectMapper.createObjectNode().put("type", "null"))));
        ObjectNode signals = objectMapper.createObjectNode()
                .put("type", "array").put("minItems", 1).put("maxItems", 10);
        signals.set("items", objectMapper.createObjectNode().put("type", "string"));
        properties.set("signals", signals);

        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.set("required", objectMapper.createArrayNode()
                .add("sceneType").add("confidence").add("reasoning")
                .add("alternativeScene").add("signals"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static JsonNode enumNode(ObjectMapper objectMapper) {
        ObjectNode node = objectMapper.createObjectNode().put("type", "string");
        node.set("enum", objectMapper.createArrayNode()
                .add("VIRAL_TOPIC").add("FESTIVAL").add("REPLENISHMENT").add("SEASONAL"));
        return node;
    }
}
