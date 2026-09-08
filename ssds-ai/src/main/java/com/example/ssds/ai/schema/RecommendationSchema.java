package com.example.ssds.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class RecommendationSchema {
    private RecommendationSchema() {}

    public static JsonNode create(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("action", enumNode(mapper, "ADOPT", "WATCH", "REJECT"));
        properties.set("qtyMin", mapper.createObjectNode()
                .put("type", "integer").put("minimum", 0));
        properties.set("qtyMax", mapper.createObjectNode()
                .put("type", "integer").put("minimum", 0));
        properties.set("quantityText", string(mapper, 1, 100));
        properties.set("reasoning", string(mapper, 1, 500));
        ObjectNode root = mapper.createObjectNode().put("type", "object");
        root.set("properties", properties);
        root.set("required", mapper.createArrayNode()
                .add("action")
                .add("qtyMin")
                .add("qtyMax")
                .add("quantityText")
                .add("reasoning"));
        root.put("additionalProperties", false);
        return root;
    }

    private static ObjectNode string(ObjectMapper mapper, int min, int max) {
        return mapper.createObjectNode()
                .put("type", "string").put("minLength", min).put("maxLength", max);
    }

    private static ObjectNode enumNode(ObjectMapper mapper, String... values) {
        ObjectNode node = mapper.createObjectNode().put("type", "string");
        var allowed = mapper.createArrayNode();
        for (String value : values) allowed.add(value);
        node.set("enum", allowed);
        return node;
    }
}
