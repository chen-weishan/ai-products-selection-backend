package com.example.ssds.ai.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** A 軌固定結構化任務：直連 Mistral Conversations API，不啟用任何外部工具。 */
@Component
public class MistralTrackAClient implements TrackAAiClient {
    private static final Logger log = LoggerFactory.getLogger(MistralTrackAClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final Set<String> verifiedReasoningModels = ConcurrentHashMap.newKeySet();

    public MistralTrackAClient(
            ObjectMapper objectMapper,
            @Value("${mistral.base-url:https://api.mistral.ai/v1}") String baseUrl,
            @Value("${mistral.api-key:}") String apiKey,
            @Value("${mistral.timeout-seconds:30}") int timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public AiClientResponse complete(AiPromptRequest request) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("MISTRAL_API_KEY 尚未設定");
        }
        verifyReasoningCapability(request.model());

        Map<String, Object> responseFormat = Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", "scene_classifier_output",
                        "strict", true,
                        "schema", request.responseSchema()));
        Map<String, Object> completionArgs = Map.of(
                "temperature", 0,
                "response_format", responseFormat);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("inputs", List.of(Map.of(
                "role", "user",
                "content", request.systemPrompt() + "\n\nINPUT_JSON:\n" + request.userPrompt())));
        body.put("completion_args", completionArgs);
        body.put("store", false);
        body.put("stream", false);

        JsonNode response = parseResponse(postConversation(request.model(), body), request.model());
        JsonNode output = findMessageOutput(response);
        String content = output.path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Mistral message.output 缺少 content");
        }
        JsonNode usage = response.path("usage");
        return new AiClientResponse(
                content,
                output.path("model").asText(request.model()),
                nullableInt(usage, "prompt_tokens"),
                nullableInt(usage, "completion_tokens"));
    }

    private void verifyReasoningCapability(String model) {
        if (verifiedReasoningModels.contains(model)) return;
        String responseBody;
        try {
            responseBody = restClient.get()
                    .uri(uriBuilder -> uriBuilder.pathSegment("models", model).build())
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException exception) {
            throw translateHttpException("model capability", model, exception);
        } catch (RestClientException exception) {
            log.warn(
                    "Mistral model capability request failed: model={}, errorType={}",
                    model,
                    exception.getClass().getSimpleName());
            throw exception;
        }
        JsonNode modelInfo = parseResponse(responseBody, model);
        if (!modelInfo.path("capabilities").path("reasoning").asBoolean(false)) {
            throw new IllegalArgumentException("Mistral 模型不支援 reasoning: " + model);
        }
        verifiedReasoningModels.add(model);
    }

    private String postConversation(String model, Map<String, Object> body) {
        try {
            return restClient.post()
                    .uri("/conversations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException exception) {
            throw translateHttpException("conversation", model, exception);
        } catch (RestClientException exception) {
            log.warn(
                    "Mistral conversation request failed: model={}, errorType={}",
                    model,
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private RuntimeException translateHttpException(
            String operation, String model, RestClientResponseException exception) {
        log.warn(
                "Mistral {} HTTP request failed: model={}, status={}",
                operation,
                model,
                exception.getStatusCode().value());
        if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
            return new AiRateLimitException("Mistral API rate limit exceeded", exception);
        }
        return exception;
    }

    private JsonNode parseResponse(String responseBody, String model) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            log.warn(
                    "Mistral response JSON parsing failed: model={}, errorType={}",
                    model,
                    exception.getClass().getSimpleName());
            throw new IllegalStateException("Mistral 回應不是有效 JSON", exception);
        }
    }

    private static JsonNode findMessageOutput(JsonNode response) {
        if (response == null || !response.path("outputs").isArray()) {
            throw new IllegalStateException("Mistral 回應缺少 outputs");
        }
        List<JsonNode> outputs = new ArrayList<>();
        response.path("outputs").forEach(outputs::add);
        for (int index = outputs.size() - 1; index >= 0; index--) {
            JsonNode output = outputs.get(index);
            if ("message.output".equals(output.path("type").asText())) return output;
        }
        throw new IllegalStateException("Mistral 回應缺少 message.output");
    }

    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.intValue();
    }
}
