package com.example.ssds.ai.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;

class MistralSourcingClientTest {
    @Test
    void recognizesActualCustomConnectorQuotaResponse() {
        HttpClientErrorException exception = response(
                HttpStatus.TOO_MANY_REQUESTS,
                "{\"detail\":\"Custom connector rate limit reached.\"}");

        assertTrue(MistralSourcingClient.isConnectorQuotaError(exception));
    }

    @Test
    void doesNotMisclassifyOrdinaryModelRateLimit() {
        HttpClientErrorException exception = response(
                HttpStatus.TOO_MANY_REQUESTS,
                "{\"detail\":\"Rate limit exceeded for this model.\"}");

        assertFalse(MistralSourcingClient.isConnectorQuotaError(exception));
    }

    private static HttpClientErrorException response(HttpStatus status, String body) {
        return HttpClientErrorException.create(
                status,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
