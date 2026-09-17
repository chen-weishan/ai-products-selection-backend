package com.example.ssds.ai.prompt.sourcing;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.model.sourcing.SourcingScoutInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SourcingScoutPromptFactoryTest {
    private final SourcingScoutPromptFactory factory = new SourcingScoutPromptFactory(new ObjectMapper());

    @Test
    void requestsOnePublicSearchAndDefinesNonBlankFallback() {
        String prompt = factory.systemPrompt();
        assertAll(
                () -> assertEquals("scout-v8", SourcingScoutPromptFactory.PROMPT_VERSION),
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
    void retryUsesValidationCodeAndRestatesTheFullContractWithoutChangingInputJson() {
        String reportCorrection = factory.retryInstruction("REPORT_INVALID");
        String evidenceCorrection = factory.retryInstruction("WEB_EVIDENCE_MISSING");
        assertAll(
                () -> assertTrue(reportCorrection.contains("REPORT_INVALID")),
                () -> assertTrue(evidenceCorrection.contains("WEB_EVIDENCE_MISSING")),
                () -> assertTrue(reportCorrection.contains("搜尋 Connector")),
                () -> assertTrue(reportCorrection.contains("report、opportunitySignals、riskSignals")),
                () -> assertTrue(reportCorrection.contains("20 至 3000")),
                () -> assertTrue(reportCorrection.contains("固定資料不足文案")),
                () -> assertTrue(reportCorrection.contains("不得輸出 heatStage")),
                () -> assertTrue(reportCorrection.contains("只輸出 JSON")));
        assertEquals(
                "{\"keyword\":\"低糖零食\",\"categoryName\":\"零食\"}",
                factory.userPrompt(new SourcingScoutInput("低糖零食", 10L, "零食")));
    }
}
