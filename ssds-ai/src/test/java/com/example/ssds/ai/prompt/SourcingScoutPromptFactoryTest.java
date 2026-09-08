package com.example.ssds.ai.prompt;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.model.SourcingScoutInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SourcingScoutPromptFactoryTest {
    private final SourcingScoutPromptFactory factory = new SourcingScoutPromptFactory(new ObjectMapper());

    @Test
    void requestsOnePublicSearchAndDefinesNonBlankFallback() {
        String prompt = factory.systemPrompt();
        assertAll(
                () -> assertEquals("scout-v6", SourcingScoutPromptFactory.PROMPT_VERSION),
                () -> assertTrue(prompt.contains("搜尋 Connector")),
                () -> assertTrue(prompt.contains("完成一次有效搜尋後即停止")),
                () -> assertFalse(prompt.contains("Google Trends")),
                () -> assertFalse(prompt.contains("open_url")),
                () -> assertFalse(prompt.contains("蝦皮")),
                () -> assertFalse(prompt.contains("momo")),
                () -> assertFalse(prompt.contains("露天")),
                () -> assertFalse(prompt.contains("Instagram")),
                () -> assertTrue(prompt.contains(SourcingScoutPromptFactory.INSUFFICIENT_REPORT)),
                () -> assertTrue(prompt.contains("不得輸出 heatStage")),
                () -> assertTrue(prompt.contains("不得留下任何空欄位")));
    }

    @Test
    void reportFailureRetryAddsCorrectionWithoutChangingInputJson() {
        String correction = factory.retryInstruction("REPORT_INVALID");
        assertTrue(correction.contains("report 為空白或長度不合格"));
        assertTrue(correction.contains("固定資料不足文案"));
        assertEquals(
                "{\"keyword\":\"低糖零食\",\"categoryId\":10,\"categoryName\":\"零食\"}",
                factory.userPrompt(new SourcingScoutInput("低糖零食", 10L, "零食")));
    }
}
