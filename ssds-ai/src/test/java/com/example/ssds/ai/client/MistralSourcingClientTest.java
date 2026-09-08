package com.example.ssds.ai.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;

class MistralSourcingClientTest {
    @Test
    void validToolBackedResponseCompletesWithoutAdditionalNetworkRoundTrip() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models/test-model", exchange -> respond(
                exchange, "{\"capabilities\":{\"reasoning\":true}}"));
        server.createContext("/v1/conversations", exchange -> respond(exchange, """
                {"outputs":[
                  {"type":"tool.execution","name":"search","status":"succeeded"},
                  {"type":"message.output","model":"test-model","content":"{}"}],
                 "usage":{"prompt_tokens":5,"completion_tokens":2}}
                """));
        server.start();
        try {
            MistralSourcingClient client = new MistralSourcingClient(
                    new ObjectMapper(),
                    new SourcingToolPolicy("exa_search", event -> {}),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-key", 2, "exa_search", event -> {});

            ScoutClientResponse response = assertTimeoutPreemptively(
                    Duration.ofSeconds(2), () -> client.complete("test-model", "test-prompt"));

            assertEquals("{}", response.content());
            assertTrue(response.searchedWeb());
            assertFalse(response.openedWebPage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void emptyConnectorConfigurationDoesNotFailBeanConstruction() {
        MistralSourcingClient client = assertDoesNotThrow(() -> new MistralSourcingClient(
                new ObjectMapper(),
                new SourcingToolPolicy("parallel_search", event -> {}),
                "http://127.0.0.1:1", "test-key", 1, "   ", event -> {}));

        assertThrows(SourcingConfigurationException.class,
                () -> client.complete("test-model", "test-prompt"));
    }

    @Test
    void disallowedConnectorFailsBeforeAnyNetworkCall() {
        MistralSourcingClient client = new MistralSourcingClient(
                new ObjectMapper(),
                new SourcingToolPolicy("exa_search", event -> {}),
                "http://127.0.0.1:1", "test-key", 1, "parallel_search", event -> {});

        assertThrows(SourcingConfigurationException.class,
                () -> client.complete("test-model", "test-prompt"));
    }

    @Test
    void invalidBTrackHttpSettingsDoNotFailBeanConstruction() {
        MistralSourcingClient client = assertDoesNotThrow(() -> new MistralSourcingClient(
                new ObjectMapper(),
                new SourcingToolPolicy("exa_search", event -> {}),
                "not-a-url", "test-key", 0, "exa_search", event -> {}));

        assertThrows(SourcingConfigurationException.class,
                () -> client.complete("test-model", "test-prompt"));
    }

    @Test
    void invalidConnectTimeoutIsIsolatedUntilBTrackInvocation() {
        MistralSourcingClient client = assertDoesNotThrow(() -> new MistralSourcingClient(
                new ObjectMapper(),
                new SourcingToolPolicy("exa_search", event -> {}),
                "http://127.0.0.1:1", "test-key", 90, 0, "exa_search", event -> {}));

        SourcingConfigurationException exception = assertThrows(
                SourcingConfigurationException.class,
                () -> client.complete("test-model", "test-prompt"));
        assertEquals("LLM_CONNECT_TIMEOUT_SECONDS 必須大於 0", exception.getMessage());
    }

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

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
