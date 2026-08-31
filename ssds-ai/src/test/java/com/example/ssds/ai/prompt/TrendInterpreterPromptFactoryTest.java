package com.example.ssds.ai.prompt;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.schema.TrendInterpreterResponseParserTest;
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
    }
}
