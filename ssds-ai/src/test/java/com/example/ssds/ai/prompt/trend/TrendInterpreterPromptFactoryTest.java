package com.example.ssds.ai.prompt.trend;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.schema.trend.TrendInterpreterResponseParserTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TrendInterpreterPromptFactoryTest {
    @Test
    void promptForbidsToolsAndOnlyAllowsBackendCandidates() {
        TrendInterpreterPromptFactory factory =
                new TrendInterpreterPromptFactory(new ObjectMapper().findAndRegisterModules());

        String system = factory.systemPrompt();
        String input = factory.userPrompt(TrendInterpreterResponseParserTest.input());

        assertTrue(system.contains("不得引入外部知識、搜尋網路或呼叫工具"));
        assertTrue(system.contains("allowedOutputs"));
        assertTrue(input.contains("compositeSeries"));
        assertTrue(input.contains("sourceTrends"));
        assertFalse(input.contains("keywordText"));
        assertEquals("trend-v3", TrendInterpreterPromptFactory.PROMPT_VERSION);
        assertTrue(factory.retryInstruction("OUTPUT_NOT_ALLOWED").contains("OUTPUT_NOT_ALLOWED"));
        assertTrue(factory.retryInstruction("OUTPUT_NOT_ALLOWED").contains("allowedOutputs"));
        assertTrue(factory.retryInstruction("OUTPUT_NOT_ALLOWED").contains("不得混搭"));
    }
}
