package com.example.ssds.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.api.admin.AiModelOptionsService.ModelOption;
import com.example.ssds.api.admin.RuntimeSettingsService.AiConfig;
import com.example.ssds.api.admin.RuntimeSettingsService.ModelRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AiModelOptionsServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("只取可對話、支援 reasoning 且未棄用的模型，依 id 排序去重")
    void parsesChatModels() throws Exception {
        String body = """
                {"data":[
                  {"id":"mistral-small-latest","capabilities":{"completion_chat":true,"reasoning":true}},
                  {"id":"mistral-embed","capabilities":{"completion_chat":false,"reasoning":true}},
                  {"id":"old-model","capabilities":{"completion_chat":true,"reasoning":true},"deprecation":"2026-01-01"},
                  {"id":"mistral-large-latest","capabilities":{"completion_chat":true,"reasoning":true}},
                  {"id":"no-reasoning","capabilities":{"completion_chat":true,"reasoning":false}},
                  {"id":"mistral-small-latest","capabilities":{"completion_chat":true,"reasoning":true}}
                ]}""";

        assertThat(AiModelOptionsService.reasoningChatModelIds(mapper.readTree(body)))
                .containsExactly("mistral-large-latest", "mistral-small-latest");
    }

    @Test
    @DisplayName("沒有金鑰：用設定清單並附說明；目前在用但不在清單的模型也列出")
    void fallsBackToConfiguredList() {
        RuntimeSettingsService settings = mock(RuntimeSettingsService.class);
        when(settings.aiConfig()).thenReturn(new AiConfig(
                Map.of("MODEL_CLASSIFY", new ModelRoute("custom-model", List.of("mistral-small-latest"))),
                1000, 0.7, 0.2, 0.1, 0.8, 20, 5, 150, 3, 30, 90, 6, 3, 3, true, true));
        AiModelOptionsService service = new AiModelOptionsService(settings, mapper, "",
                "mistral-small-latest, mistral-large-latest", RestClient.create(), Clock.systemUTC());

        var options = service.options();

        assertThat(options.source()).isEqualTo("CONFIGURED_LIST");
        assertThat(options.warning()).contains("MISTRAL_API_KEY");
        assertThat(options.models()).containsExactly(
                new ModelOption("mistral-small-latest", false, true),
                new ModelOption("mistral-large-latest", false, false),
                new ModelOption("custom-model", false, true));
        assertThat(options.aliases()).extracting(AiModelOptionsService.AliasInfo::code).containsExactly(
                "MODEL_CLASSIFY", "MODEL_LONG_TEXT", "MODEL_SHORT_GEN", "MODEL_NUMERIC", "MODEL_REASONING");
    }

    @Test
    @DisplayName("儲存模型路由前拒絕 reasoning=false")
    void rejectsModelWithoutReasoning() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models/no-reasoning", exchange -> {
            byte[] body = "{\"capabilities\":{\"reasoning\":false}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RuntimeSettingsService settings = mock(RuntimeSettingsService.class);
            AiModelOptionsService service = new AiModelOptionsService(
                    settings,
                    mapper,
                    "test-key",
                    "",
                    RestClient.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    Clock.systemUTC());
            AiConfig config = new AiConfig(
                    Map.of("MODEL_CLASSIFY", new ModelRoute("no-reasoning", List.of())),
                    1000, 0.7, 0.2, 0.1, 0.8, 20, 5, 150, 3, 30, 90, 6, 3, 3, true, true);

            assertThatThrownBy(() -> service.validateReasoningModels(config))
                    .isInstanceOf(com.example.ssds.api.common.error.BusinessException.class)
                    .hasMessageContaining("不支援 reasoning=true");
        } finally {
            server.stop(0);
        }
    }
}
