package com.example.ssds.ai.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ExternalLlmPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsDatabaseIdsAndRawSupplierFields() {
        ExternalLlmPolicy policy = new ExternalLlmPolicy(true, mapper);

        assertThrows(OutboundDataPolicyException.class,
                () -> policy.validateUserJson("{\"productId\":101}"));
        assertThrows(OutboundDataPolicyException.class,
                () -> policy.validateUserJson("{\"product\":{\"supplierName\":\"真實供應商\"}}"));
        assertThrows(OutboundDataPolicyException.class,
                () -> policy.validateUserJson("{\"quote\":120}"));
    }

    @Test
    void allowsRequestLocalIndexesAndSupplierPolicyInstructions() {
        ExternalLlmPolicy policy = new ExternalLlmPolicy(true, mapper);

        assertDoesNotThrow(() -> policy.validateUserJson(
                "{\"reviews\":[{\"reviewIndex\":0,\"content\":\"不得評價供應商\"}]}"));
    }

    @Test
    void disabledPolicyRejectsBeforePayloadProcessing() {
        ExternalLlmPolicy policy = new ExternalLlmPolicy(false, mapper);

        assertThrows(ExternalLlmDisabledException.class,
                () -> policy.validateUserJson("not-json"));
    }
}
