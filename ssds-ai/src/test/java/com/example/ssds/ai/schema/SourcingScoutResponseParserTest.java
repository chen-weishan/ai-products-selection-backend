package com.example.ssds.ai.schema;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.core.domain.HeatStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SourcingScoutResponseParserTest {
    private final SourcingScoutResponseParser parser = new SourcingScoutResponseParser(new ObjectMapper());

    @Test
    void parsesStrictCleanJson() {
        var output = parser.parse("""
                {"report":"本次已取得電商與趨勢來源，市場需求呈現上升，但資料涵蓋仍有限。","opportunitySignals":["需求上升"],
                 "riskSignals":["供應資訊不足"],"heatStage":"RISING"}
                """);
        assertEquals(HeatStage.RISING, output.heatStage());
        assertTrue(output.report().startsWith("本次已取得"));
    }

    @Test
    void extractsUniqueJsonFromMarkdownOrSurroundingText() {
        var fenced = parser.parse("""
                ```json
                {"report":"本次已取得兩個相關來源，市場訊號穩定，但仍需持續觀察資料變化。","opportunitySignals":["a"],"riskSignals":["b"],"heatStage":"PLATEAU"}
                ```
                """);
        var prefixed = parser.parse("""
                以下為探索結果：
                {"report":"本次報告包含 {括號} 與 \\"引號\\"，並保留足夠市場觀察及資料限制說明。","opportunitySignals":["a"],
                 "riskSignals":["b"],"heatStage":"DECLINING"}
                請參考以上內容。
                """);
        assertEquals(HeatStage.PLATEAU, fenced.heatStage());
        assertEquals(HeatStage.DECLINING, prefixed.heatStage());
    }

    @Test
    void rejectsExtraFieldsOrMultipleCandidateObjects() {
        assertThrows(AiSchemaValidationException.class, () -> parser.parse("""
                {"report":"本次已取得足夠來源並形成市場觀察，但此物件包含不允許的額外欄位。","opportunitySignals":["a"],"riskSignals":["b"],
                 "heatStage":"PLATEAU","extra":true}
                """));
        assertThrows(AiSchemaValidationException.class, () -> parser.parse("""
                {"report":"第一個候選報告已取得來源並形成市場觀察，但同一回應不應有第二個物件。","opportunitySignals":["a"],"riskSignals":["b"],"heatStage":"RISING"}
                {"report":"第二個候選報告已取得來源並形成市場觀察，但同一回應不應有兩個物件。","opportunitySignals":["a"],"riskSignals":["b"],"heatStage":"PLATEAU"}
                """));
    }

    @Test
    void rejectsBlankOrShortReport() {
        assertThrows(AiSchemaValidationException.class, () -> parser.parse("""
                {"report":"   ","opportunitySignals":["a"],"riskSignals":["b"],"heatStage":"PLATEAU"}
                """));
        assertThrows(AiSchemaValidationException.class, () -> parser.parse("""
                {"report":"內容太短","opportunitySignals":["a"],"riskSignals":["b"],"heatStage":"PLATEAU"}
                """));
    }
}
