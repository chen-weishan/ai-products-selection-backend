package com.example.ssds.ai.prompt;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.schema.RecommendationResponseParserTest;
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
    }
}
