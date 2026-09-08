package com.example.ssds.ai.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.ssds.ai.model.SceneClassifierOutput;
import com.example.ssds.ai.model.SceneClassifierInput;
import com.example.ssds.ai.model.FestivalMatch;
import com.example.ssds.ai.model.HeatBucket;
import com.example.ssds.ai.model.SceneCode;
import com.example.ssds.core.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
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
                  "sceneType": "VIRAL",
                  "confidence": 0.82,
                  "reasoning": "熱度上升，補充說明包含 {括號}。",
                  "alternativeScene": "SEASONAL",
                  "signals": ["heatSlopePercentile: 88.00"]
                }
                ```
                """, input());

        assertEquals(SceneCode.VIRAL, output.sceneType());
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
                    "signals": ["festivalMatches: MID_AUTUMN=0.45"]
                  }
                }
                """, input());

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
                    "signals": ["festivalMatches: MID_AUTUMN=0.45"]
                  }
                }
                """, input()));
    }

    @Test
    void extractedObjectStillRejectsAdditionalFields() {
        assertThrows(AiSchemaValidationException.class, () -> parser.parse("""
                result follows:
                {
                  "sceneType": "VIRAL",
                  "confidence": 0.82,
                  "reasoning": "熱度上升",
                  "alternativeScene": null,
                  "signals": ["heatSlopePercentile: 88.00"],
                  "weights": {"TREND": 0.9}
                }
                """, input()));
    }

    @Test
    void rejectsNumberInNarrativeThatWasNotSentToModel() {
        assertThrows(AiSchemaValidationException.class, () -> parser.parse("""
                {
                  "sceneType":"VIRAL",
                  "confidence":0.82,
                  "reasoning":"熱度已連續上升 12 週",
                  "alternativeScene":null,
                  "signals":["heatSlopePercentile: 88.00"]
                }
                """, input()));
    }

    private static SceneClassifierInput input() {
        return new SceneClassifierInput(
                101L, "日式抹茶餅乾", 10L, "進口零食", Season.SUMMER,
                new BigDecimal("3.40"), new BigDecimal("1.25"), new BigDecimal("88.00"),
                HeatStage.RISING, HeatBucket.VERY_HIGH, 2,
                List.of(new FestivalMatch("MID_AUTUMN", new BigDecimal("0.45"))));
    }
}
