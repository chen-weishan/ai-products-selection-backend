package com.example.ssds.ai.prompt.recommendation;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.schema.recommendation.RecommendationResponseParserTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RecommendationPromptFactoryTest {
    @Test
    void promptContainsOnlyPercentilesAndExplicitlyForbidsRawValuesAndTools() {
        RecommendationPromptFactory factory = new RecommendationPromptFactory(new ObjectMapper());

        String input = factory.userPrompt(RecommendationResponseParserTest.input());
        String system = factory.systemPrompt();

        assertTrue(input.contains("percentile"));
        assertTrue(input.contains("allowedQuantities"));
        assertFalse(input.contains("rawValue"));
        assertFalse(input.contains("cost"));
        assertFalse(input.contains("suggestedPrice"));
        assertFalse(input.contains("marginRate"));
        assertFalse(input.contains("supplier"));
        assertTrue(system.contains("不得搜尋網路或呼叫工具"));
        assertTrue(system.contains("數字必須與 INPUT_JSON 完全相同"));
        assertEquals("recommendation-v4", RecommendationPromptFactory.PROMPT_VERSION);
        assertTrue(system.contains("自然、流暢的繁體中文"));
        assertTrue(system.contains("不是逐字替換表"));
        assertTrue(system.contains("社群熱度與歷史轉換表現突出"));
        assertTrue(factory.retryInstruction("QUANTITY_INVALID").contains("QUANTITY_INVALID"));
        assertTrue(factory.retryInstruction("QUANTITY_INVALID").contains("allowedQuantities"));
        assertTrue(factory.retryInstruction("SCHEMA_INVALID").contains("只輸出 JSON"));
        assertTrue(factory.retryInstruction("LANGUAGE_INVALID").contains("不得出現英文內部欄位"));
    }
}
