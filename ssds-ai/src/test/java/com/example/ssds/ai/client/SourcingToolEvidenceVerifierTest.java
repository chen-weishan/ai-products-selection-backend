package com.example.ssds.ai.client;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SourcingToolEvidenceVerifierTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void acceptsExecutionFromTheOnlyConfiguredConnector() throws Exception {
        var response = mapper.readTree("""
                {"outputs":[
                  {"type":"tool.execution","name":"search","function":"search"},
                  {"type":"message.output","content":"{}"}]}
                """);
        assertEquals("{}", SourcingToolEvidenceVerifier.verifiedMessageOutput(response, "exa_search").path("content").asText());
    }
    @Test void rejectsModelOnlyAnswer() throws Exception {
        assertThrows(ScoutToolEvidenceException.class, () ->
                SourcingToolEvidenceVerifier.verifiedMessageOutput(
                        mapper.readTree("{\"outputs\":[{\"type\":\"message.output\"}]}"), "exa_search"));
    }
}
