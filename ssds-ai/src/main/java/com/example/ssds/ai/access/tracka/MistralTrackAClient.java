package com.example.ssds.ai.access.tracka;

import com.example.ssds.ai.access.common.AiModelNotFoundException;
import com.example.ssds.ai.access.common.AiModelUnavailableEvent;
import com.example.ssds.ai.access.common.AiExecutionWarningContext;
import com.example.ssds.ai.budget.DailyAiBudget;
import com.example.ssds.ai.policy.ExternalLlmPolicy;
import com.example.ssds.ai.resilience.AiRateLimitException;
import com.example.ssds.core.domain.AiTaskType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;
    private final DailyAiBudget budget;
    private final ExternalLlmPolicy externalLlmPolicy;
    private final Set<String> verifiedReasoningModels = ConcurrentHashMap.newKeySet();

    @Autowired
    public MistralTrackAClient(
            ObjectMapper objectMapper,
            @Value("${mistral.base-url:https://api.mistral.ai/v1}") String baseUrl,
            @Value("${mistral.api-key:}") String apiKey,
            @Value("${mistral.timeout-seconds:30}") int timeoutSeconds,
            @Value("${mistral.connect-timeout-seconds:10}") int connectTimeoutSeconds,
            DailyAiBudget budget,
            ApplicationEventPublisher eventPublisher,
            ExternalLlmPolicy externalLlmPolicy) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.eventPublisher = eventPublisher;
        this.budget = budget;
        this.externalLlmPolicy = externalLlmPolicy;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(
                                effectiveConnectTimeout(connectTimeoutSeconds, timeoutSeconds)))
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    MistralTrackAClient(ObjectMapper objectMapper, String baseUrl, String apiKey, int timeoutSeconds) {
        this(objectMapper, baseUrl, apiKey, timeoutSeconds, defaultConnectTimeout(timeoutSeconds),
                new DailyAiBudget(1000, 0.7, 0.2, 0.1, java.time.Clock.systemUTC()), event -> {},
                new ExternalLlmPolicy(true, objectMapper));
    }

    MistralTrackAClient(
            ObjectMapper objectMapper,
            String baseUrl,
            String apiKey,
            int timeoutSeconds,
            int connectTimeoutSeconds,
            DailyAiBudget budget,
            ApplicationEventPublisher eventPublisher) {
        this(objectMapper, baseUrl, apiKey, timeoutSeconds, connectTimeoutSeconds, budget, eventPublisher,
                new ExternalLlmPolicy(true, objectMapper));
    }

    MistralTrackAClient(
            ObjectMapper objectMapper,
            String baseUrl,
            String apiKey,
            int timeoutSeconds,
            ApplicationEventPublisher eventPublisher) {
        this(objectMapper, baseUrl, apiKey, timeoutSeconds, defaultConnectTimeout(timeoutSeconds),
                new DailyAiBudget(1000, 0.7, 0.2, 0.1, java.time.Clock.systemUTC()), eventPublisher,
                new ExternalLlmPolicy(true, objectMapper));
    }

    private static int defaultConnectTimeout(int readTimeoutSeconds) {
        return Math.min(10, Math.max(1, readTimeoutSeconds));
    }

    private static int effectiveConnectTimeout(int connectTimeoutSeconds, int readTimeoutSeconds) {
        if (connectTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("LLM_CONNECT_TIMEOUT_SECONDS 必須大於 0");
        }
        if (readTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("LLM_TIMEOUT_SECONDS 必須大於 0");
        }
        return Math.min(connectTimeoutSeconds, readTimeoutSeconds);
    }

    @Override
    public AiClientResponse complete(AiPromptRequest request) {
        externalLlmPolicy.validateUserJson(request.userPrompt());
        if (apiKey.isBlank()) {
            throw new IllegalStateException("MISTRAL_API_KEY 尚未設定");
        }
        verifyReasoningCapability(request.taskType(), request.model());

        Map<String, Object> responseFormat = Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", request.taskType().name().toLowerCase() + "_output",
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

        budget.acquire(request.taskType().budgetPool(), request.retryAttempt());
        JsonNode response = parseResponse(
                postConversation(request.taskType(), request.model(), body), request.model());
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

    private void verifyReasoningCapability(AiTaskType taskType, String model) {
        if (verifiedReasoningModels.contains(model)) return;
        String responseBody;
        try {
            responseBody = restClient.get()
                    .uri(uriBuilder -> uriBuilder.pathSegment("models", model).build())
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException exception) {
            throw translateHttpException("model capability", taskType, model, exception);
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

    private String postConversation(
            AiTaskType taskType,
            String model,
            Map<String, Object> body) {
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
            throw translateHttpException("conversation", taskType, model, exception);
        } catch (RestClientException exception) {
            log.warn(
                    "Mistral conversation request failed: model={}, errorType={}",
                    model,
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private RuntimeException translateHttpException(
            String operation,
            AiTaskType taskType,
            String model,
            RestClientResponseException exception) {
        log.warn(
                "Mistral {} HTTP request failed: model={}, status={}",
                operation,
                model,
                exception.getStatusCode().value());
        if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
            return new AiRateLimitException("Mistral API rate limit exceeded", exception);
        }
        if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
            AiModelUnavailableEvent event = new AiModelUnavailableEvent(
                    modelAlias(taskType), model, exception.getStatusCode().value());
            AiExecutionWarningContext.record(event);
            eventPublisher.publishEvent(event);
            return new AiModelNotFoundException(model, exception);
        }
        return exception;
    }

    private static String modelAlias(AiTaskType taskType) {
        return switch (taskType) {
            case FULL_ANALYSIS -> "FULL_ANALYSIS";
            case SCENE_CLASSIFY -> "MODEL_CLASSIFY";
            case REVIEW_RISK, SELLING_POINT -> "MODEL_LONG_TEXT";
            case RECOMMENDATION -> "MODEL_SHORT_GEN";
            case TREND_INTERPRET -> "MODEL_NUMERIC";
            case SOURCING_SCOUT, WEIGHT_CALIBRATION -> "MODEL_REASONING";
        };
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
