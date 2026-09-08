package com.example.ssds.ai.schema;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class WeightCalibrationSchema {
    private WeightCalibrationSchema() {}
    public static JsonNode create(ObjectMapper mapper) {
        ObjectNode adviceProps = mapper.createObjectNode();
        adviceProps.set("factorCode", string(mapper, 1, 32));
        adviceProps.set("explanation", string(mapper, 1, 500));
        ObjectNode props = mapper.createObjectNode();
        props.set("report", string(mapper, 1, 3000));
        props.set("adjustmentAdvice", array(mapper,
                object(mapper, adviceProps, "factorCode", "explanation"), 1, 6));
        props.set("attentionNotes", array(mapper, string(mapper, 1, 500), 1, 6));
        return object(mapper, props, "report", "adjustmentAdvice", "attentionNotes");
    }
    private static ObjectNode string(ObjectMapper m, int min, int max) {
        return m.createObjectNode().put("type", "string").put("minLength", min).put("maxLength", max);
    }
    private static ObjectNode array(ObjectMapper m, JsonNode items, int min, int max) {
        ObjectNode n=m.createObjectNode().put("type","array").put("minItems",min).put("maxItems",max); n.set("items",items); return n;
    }
    private static ObjectNode object(ObjectMapper m, ObjectNode props, String... required) {
        ObjectNode n=m.createObjectNode().put("type","object").put("additionalProperties",false); n.set("properties",props);
        var r=m.createArrayNode(); for(String value:required) r.add(value); n.set("required",r); return n;
    }
}
