package com.example.ssds.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class TrendInterpreterSchema {
    private TrendInterpreterSchema() {}

    public static JsonNode create(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("stage", enumNode(mapper, "RISING", "PLATEAU", "DECLINING"));
        properties.set("stageWeeks", mapper.createObjectNode()
                .put("type", "integer").put("minimum", 1));
        properties.set("estimatedLifespanDays", mapper.createObjectNode()
                .put("type", "integer").put("minimum", 0));
        ObjectNode root = mapper.createObjectNode().put("type", "object");
        root.set("properties", properties);
        root.set("required", mapper.createArrayNode()
                .add("stage").add("stageWeeks").add("estimatedLifespanDays"));
        root.put("additionalProperties", false);
        return root;
    }

    private static ObjectNode enumNode(ObjectMapper mapper, String... values) {
        ObjectNode node = mapper.createObjectNode().put("type", "string");
        var allowed = mapper.createArrayNode();
        for (String value : values) allowed.add(value);
        node.set("enum", allowed);
        return node;
    }
}
