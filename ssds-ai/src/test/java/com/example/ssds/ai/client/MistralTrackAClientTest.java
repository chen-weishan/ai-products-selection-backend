package com.example.ssds.ai.client;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.core.domain.AiTaskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MistralTrackAClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void extractsFinalMessageOutputAndDoesNotSendTools() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/v1/models/")) {
                respond(exchange, 200, """
                        {"id":"test-model","capabilities":{"reasoning":true}}
                        """);
                return;
            }
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {
                      "object":"conversation.response",
                      "outputs":[
                        {"type":"tool.execution","name":"ignored"},
                        {
                          "type":"message.output",
                          "model":"test-model",
                          "content":"{\\\"sceneType\\\":\\\"VIRAL_TOPIC\\\",\\\"confidence\\\":0.82,\\\"reasoning\\\":\\\"熱度上升\\\",\\\"alternativeScene\\\":null,\\\"signals\\\":[\\\"heatSlope7d: 3.40\\\"]}"
                        }
                      ],
                      "usage":{"prompt_tokens":120,"completion_tokens":45}
                    }
                    """);
        });

        AiClientResponse response = client().complete(request());

        assertEquals("test-model", response.model());
        assertTrue(response.content().contains("\"sceneType\":\"VIRAL_TOPIC\""));
        assertEquals(120, response.promptTokens());
        assertEquals(45, response.completionTokens());
        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertFalse(sent.has("tools"));
        assertFalse(sent.path("store").asBoolean(true));
        assertEquals("json_schema", sent.path("completion_args").path("response_format").path("type").asText());
        assertTrue(sent.path("inputs").get(0).path("content").asText().contains("INPUT_JSON"));
    }

    @Test
    void rejectsModelWithoutReasoningCapabilityBeforeConversationCall() throws Exception {
        AtomicInteger conversations = new AtomicInteger();
        startServer(exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/v1/models/")) {
                respond(exchange, 200, """
                        {"id":"test-model","capabilities":{"reasoning":false}}
                        """);
                return;
            }
            conversations.incrementAndGet();
            respond(exchange, 500, "{}");
        });

        assertThrows(IllegalArgumentException.class, () -> client().complete(request()));
        assertEquals(0, conversations.get());
    }

    @Test
    void translatesHttp429ToRateLimitException() throws Exception {
        startServer(exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/v1/models/")) {
                respond(exchange, 200, """
                        {"id":"test-model","capabilities":{"reasoning":true}}
                        """);
                return;
            }
            respond(exchange, 429, "{\"message\":\"rate limited\"}");
        });

        assertThrows(AiRateLimitException.class, () -> client().complete(request()));
    }

    private MistralTrackAClient client() {
        return new MistralTrackAClient(
                objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key",
                5);
    }

    private AiPromptRequest request() {
        return new AiPromptRequest(
                AiTaskType.SCENE_CLASSIFY,
                "test-model",
                "system instructions",
                "{\"productId\":101}",
                objectMapper.createObjectNode().put("type", "object"));
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
                handler.handle(exchange);
            } catch (Throwable exception) {
                respond(exchange, 500, "{}");
            }
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
