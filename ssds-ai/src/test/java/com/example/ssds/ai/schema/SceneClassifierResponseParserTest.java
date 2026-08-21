package com.example.ssds.ai.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.ssds.ai.model.SceneClassifierOutput;
import com.example.ssds.ai.model.SceneCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SceneClassifierResponseParserTest {
    private final SceneClassifierResponseParser parser =
            new SceneClassifierResponseParser(new ObjectMapper());

    @Test
    void extractsSceneJsonFromMarkdownAndSurroundingText() {
        SceneClassifierOutput output = parser.parse("""
                以下是判定結果：
                ```json
                {
                  "sceneType": "VIRAL_TOPIC",
                  "confidence": 0.82,
                  "reasoning": "熱度上升，補充說明包含 {括號}。",
                  "alternativeScene": "SEASONAL",
                  "signals": ["heatSlope7d: 3.40"]
                }
                ```
                """);

        assertEquals(SceneCode.VIRAL_TOPIC, output.sceneType());
        assertEquals("熱度上升，補充說明包含 {括號}。", output.reasoning());
    }

    @Test
    void unwrapsSingleKnownEnvelope() {
        SceneClassifierOutput output = parser.parse("""
                {
                  "classification": {
                    "sceneType": "FESTIVAL",
                    "confidence": 0.76,
                    "reasoning": "節慶匹配明確",
                    "alternativeScene": null,
                    "signals": ["festivalMatches: MID_AUTUMN=0.90"]
                  }
                }
                """);

        assertEquals(SceneCode.FESTIVAL, output.sceneType());
    }

    @Test
    void rejectsUnknownEnvelope() {
        assertThrows(AiSchemaValidationException.class, () -> parser.parse("""
                {
                  "answer": {
                    "sceneType": "FESTIVAL",
                    "confidence": 0.76,
                    "reasoning": "節慶匹配明確",
                    "alternativeScene": null,
                    "signals": ["festivalMatches: MID_AUTUMN=0.90"]
                  }
                }
                """));
    }

    @Test
    void extractedObjectStillRejectsAdditionalFields() {
        assertThrows(AiSchemaValidationException.class, () -> parser.parse("""
                result follows:
                {
                  "sceneType": "VIRAL_TOPIC",
                  "confidence": 0.82,
                  "reasoning": "熱度上升",
                  "alternativeScene": null,
                  "signals": ["heatSlope7d: 3.40"],
                  "weights": {"TREND": 0.9}
                }
                """));
    }
}
