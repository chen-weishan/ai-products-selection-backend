package com.example.ssds.ai.prompt.review;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.model.review.ReviewRiskInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewRiskPromptFactoryTest {
    @Test
    void retryInstructionRestatesParserCriticalRules() {
        ReviewRiskPromptFactory factory = new ReviewRiskPromptFactory(new ObjectMapper());

        String instruction = factory.retryInstruction("RATIO_INVALID");
        String systemPrompt = factory.systemPrompt();

        assertAll(
                () -> assertEquals("review-risk-v5", ReviewRiskPromptFactory.PROMPT_VERSION),
                () -> assertTrue(instruction.contains("RATIO_INVALID")),
                () -> assertTrue(instruction.contains("reviewIndex 不得遺漏、重複或超出範圍")),
                () -> assertTrue(instruction.contains("只有 NEGATIVE")),
                () -> assertTrue(instruction.contains("該主題負評數 / negativeTotal")),
                () -> assertTrue(instruction.contains("禁止 count、negativeCount、reason")),
                () -> assertTrue(systemPrompt.contains(
                        "topicStatistics[] 每個物件必須且只能包含 topic、ratio、severity")),
                () -> assertTrue(instruction.contains("只輸出 JSON")));
    }

    @Test
    void userPromptCarriesExactOutputCardinalityAndOrdering() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ReviewRiskPromptFactory factory = new ReviewRiskPromptFactory(mapper);
        ReviewRiskInput input = new ReviewRiskInput(99L, List.of(
                new ReviewRiskInput.ReviewText(10L, "第一則"),
                new ReviewRiskInput.ReviewText(11L, "第二則"),
                new ReviewRiskInput.ReviewText(12L, "第三則")));

        JsonNode prompt = mapper.readTree(factory.userPrompt(input));

        assertAll(
                () -> assertEquals(3, prompt.get("reviewCount").intValue()),
                () -> assertEquals(List.of(0, 1, 2), mapper.convertValue(
                        prompt.get("requiredReviewIndexes"),
                        mapper.getTypeFactory().constructCollectionType(List.class, Integer.class))),
                () -> assertEquals(5, prompt.get("requiredTopicOrder").size()),
                () -> assertEquals(3, prompt.get("reviews").size()));
    }
}
