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

    @Test void recordsOpenUrlWithoutRequiringIt() throws Exception {
        var response = mapper.readTree("""
                {"outputs":[
                  {"type":"tool.execution","connector_id":"exa_search","name":"search","status":"succeeded"},
                  {"type":"tool.execution","connector_id":"exa_search","function":"open_url","status":"succeeded"},
                  {"type":"message.output","content":"{}"}]}
                """);

        var evidence = SourcingToolEvidenceVerifier.verify(response, "exa_search");

        assertTrue(evidence.searchedWeb());
        assertTrue(evidence.openedWebPage());
    }

    @Test void rejectsModelOnlyAnswer() throws Exception {
        assertThrows(ScoutToolEvidenceException.class, () ->
                SourcingToolEvidenceVerifier.verifiedMessageOutput(
                        mapper.readTree("{\"outputs\":[{\"type\":\"message.output\"}]}"), "exa_search"));
    }

    @Test void rejectsDifferentConnectorWhenResponseIdentifiesIt() throws Exception {
        var response = mapper.readTree("""
                {"outputs":[
                  {"type":"tool.execution","connector_id":"other_connector","name":"search"},
                  {"type":"message.output","content":"{}"}]}
                """);

        assertThrows(ScoutToolEvidenceException.class,
                () -> SourcingToolEvidenceVerifier.verify(response, "exa_search"));
    }

    @Test void rejectsFailedExecutionAsEvidence() throws Exception {
        var response = mapper.readTree("""
                {"outputs":[
                  {"type":"tool.execution","name":"search","status":"failed","error":"connector unavailable"},
                  {"type":"message.output","content":"{}"}]}
                """);

        assertThrows(ScoutToolEvidenceException.class,
                () -> SourcingToolEvidenceVerifier.verify(response, "exa_search"));
    }

    @Test void rejectsMessageProducedBeforeToolExecution() throws Exception {
        var response = mapper.readTree("""
                {"outputs":[
                  {"type":"message.output","content":"{}"},
                  {"type":"tool.execution","name":"search"}]}
                """);

        assertThrows(ScoutToolEvidenceException.class,
                () -> SourcingToolEvidenceVerifier.verify(response, "exa_search"));
    }
}
