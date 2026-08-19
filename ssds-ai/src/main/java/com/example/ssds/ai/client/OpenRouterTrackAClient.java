package com.example.ssds.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenRouterTrackAClient implements TrackAAiClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public OpenRouterTrackAClient(
            ObjectMapper objectMapper,
            @Value("${openrouter.base-url:https://openrouter.ai/api/v1}") String baseUrl,
            @Value("${openrouter.api-key:}") String apiKey,
            @Value("${openrouter.timeout-seconds:30}") int timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public AiClientResponse complete(AiPromptRequest request) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("OPENROUTER_API_KEY 尚未設定");
        }
        Map<String, Object> body = Map.of(
                "model", request.model(),
                "messages", List.of(
                        Map.of("role", "system", "content", request.systemPrompt()),
                        Map.of("role", "user", "content", request.userPrompt())),
                "response_format", Map.of(
                        "type", "json_schema",
                        "json_schema", Map.of(
                                "name", "scene_classifier_output",
                                "strict", true,
                                "schema", request.responseSchema())),
                "provider", Map.of(
                        "require_parameters", true,
                        "data_collection", "deny"),
                "stream", false);

        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(apiKey))
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.path("choices").isEmpty()) {
            throw new IllegalStateException("OpenRouter 回應缺少 choices");
        }
        JsonNode choice = response.path("choices").get(0);
        if ("error".equals(choice.path("finish_reason").asText())) {
            throw new IllegalStateException("OpenRouter provider 回應失敗");
        }
        String content = choice.path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("OpenRouter 回應缺少 message.content");
        }
        JsonNode usage = response.path("usage");
        return new AiClientResponse(
                content,
                response.path("model").asText(request.model()),
                nullableInt(usage, "prompt_tokens"),
                nullableInt(usage, "completion_tokens"));
    }

    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.intValue();
    }
}
