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
        assertTrue(system.contains("RISING：最新 slope30d 大於 0.10"));
        assertTrue(system.contains("PLATEAU：最新 slope30d 落在 -0.10 至 0.10（包含邊界）"));
        assertTrue(system.contains("DECLINING：最新 slope30d 小於 -0.10"));
        assertTrue(system.contains("slope30d 缺失"));
        assertTrue(input.contains("compositeSeries"));
        assertTrue(input.contains("sourceTrends"));
        assertFalse(input.contains("keywordText"));
        assertEquals("trend-v4", TrendInterpreterPromptFactory.PROMPT_VERSION);
        assertTrue(factory.retryInstruction("OUTPUT_NOT_ALLOWED").contains("OUTPUT_NOT_ALLOWED"));
        assertTrue(factory.retryInstruction("OUTPUT_NOT_ALLOWED").contains("allowedOutputs"));
        assertTrue(factory.retryInstruction("OUTPUT_NOT_ALLOWED").contains("不得混搭"));
    }
}
