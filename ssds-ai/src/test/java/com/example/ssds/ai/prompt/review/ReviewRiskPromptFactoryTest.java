package com.example.ssds.ai.prompt.review;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ReviewRiskPromptFactoryTest {
    @Test
    void retryInstructionRestatesParserCriticalRules() {
        ReviewRiskPromptFactory factory = new ReviewRiskPromptFactory(new ObjectMapper());

        String instruction = factory.retryInstruction("RATIO_INVALID");

        assertAll(
                () -> assertEquals("review-risk-v4", ReviewRiskPromptFactory.PROMPT_VERSION),
                () -> assertTrue(instruction.contains("RATIO_INVALID")),
                () -> assertTrue(instruction.contains("reviewIndex 不得遺漏、重複或超出範圍")),
                () -> assertTrue(instruction.contains("只有 NEGATIVE")),
                () -> assertTrue(instruction.contains("ratio 必須與逐筆負評分類一致")),
                () -> assertTrue(instruction.contains("只輸出 JSON")));
    }
}
