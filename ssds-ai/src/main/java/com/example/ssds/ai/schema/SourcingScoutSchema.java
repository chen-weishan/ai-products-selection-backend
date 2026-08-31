package com.example.ssds.ai.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class SourcingScoutSchema {
    private SourcingScoutSchema() {}

    public static ObjectNode create(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        properties.putObject("report")
                .put("type", "string")
                .put("minLength", 20)
                .put("maxLength", 3000)
                .put("pattern", ".*\\S.*");
        array(properties, "opportunitySignals");
        array(properties, "riskSignals");
        properties.putObject("heatStage").put("type", "string")
                .putArray("enum").add("RISING").add("PLATEAU").add("DECLINING");
        root.putArray("required").add("report").add("opportunitySignals")
                .add("riskSignals").add("heatStage");
        return root;
    }

    private static void array(ObjectNode properties, String name) {
        ObjectNode value = properties.putObject(name);
        value.put("type", "array").put("minItems", 1).put("maxItems", 5);
        value.putObject("items").put("type", "string").put("minLength", 1);
    }
}
