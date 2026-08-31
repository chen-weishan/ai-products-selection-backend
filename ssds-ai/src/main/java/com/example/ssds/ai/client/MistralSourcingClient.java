package com.example.ssds.ai.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

/** B 軌直連 Mistral Conversations，每次請求只啟用一個搜尋 Connector。 */
@Component
public class MistralSourcingClient {
    private static final Logger log = LoggerFactory.getLogger(MistralSourcingClient.class);
    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final int timeoutSeconds;
    private final SourcingToolPolicy toolPolicy;
    private final ApplicationEventPublisher eventPublisher;
    private final List<String> connectors;
    private final AtomicInteger connectorCursor = new AtomicInteger();
    private final Set<String> verifiedModels = ConcurrentHashMap.newKeySet();

    @Autowired
    public MistralSourcingClient(
            ObjectMapper mapper,
            SourcingToolPolicy toolPolicy,
            @Value("${mistral.base-url:https://api.mistral.ai/v1}") String baseUrl,
            @Value("${mistral.api-key:}") String apiKey,
            @Value("${mistral.sourcing-timeout-seconds:90}") int timeoutSeconds,
            @Value("${mistral.sourcing-connectors:parallel_search,exa_search,tavily_search}") String connectors,
            ApplicationEventPublisher eventPublisher) {
        this.mapper = mapper;
        this.toolPolicy = toolPolicy;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
        this.eventPublisher = eventPublisher;
        this.connectors = parseConnectors(connectors);
        this.connectors.forEach(toolPolicy::requireAllowed);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public ScoutClientResponse complete(String model, String prompt) {
        if (apiKey.isBlank()) throw new IllegalStateException("MISTRAL_API_KEY 尚未設定");
        verifyReasoning(model);
        int connectorIndex = Math.floorMod(connectorCursor.get(), connectors.size());
        String connector = connectors.get(connectorIndex);
        toolPolicy.requireAllowed(connector);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("inputs", List.of(Map.of("role", "user", "content", prompt)));
        body.put("tools", List.of(Map.of("type", "connector", "connector_id", connector)));
        body.put("completion_args", Map.of("temperature", 0));
        body.put("store", false);
        body.put("stream", false);

        JsonNode response;
        try {
            log.info("Mistral sourcing connector selected: model={}, connector={}", model, connector);
            response = parse(post(model, connector, body), model);
        } catch (AiRateLimitException exception) {
            rotateConnector(connectorIndex);
            log.warn("Mistral sourcing connector limited; next retry will rotate: connector={}, nextConnector={}",
                    connector, connectors.get(Math.floorMod(connectorCursor.get(), connectors.size())));
            throw exception;
        }
        JsonNode message = SourcingToolEvidenceVerifier.verifiedMessageOutput(response, connector);
        String content = extractContent(message.path("content"));
        JsonNode usage = response.path("usage");
        return new ScoutClientResponse(content, message.path("model").asText(model),
                nullableInt(usage, "prompt_tokens"), nullableInt(usage, "completion_tokens"), true, true);
    }

    private void verifyReasoning(String model) {
        if (verifiedModels.contains(model)) return;
        JsonNode info;
        try {
            String body = restClient.get().uri(uri -> uri.pathSegment("models", model).build())
                    .headers(h -> h.setBearerAuth(apiKey)).retrieve().body(String.class);
            info = parse(body, model);
        } catch (RestClientResponseException exception) { throw translate(model, exception); }
        if (!info.path("capabilities").path("reasoning").asBoolean(false))
            throw new IllegalArgumentException("Mistral 模型不支援 reasoning: " + model);
        verifiedModels.add(model);
    }

    private String post(String model, String connector, Map<String, Object> body) {
        try {
            return restClient.post().uri("/conversations")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .headers(h -> h.setBearerAuth(apiKey)).body(body).retrieve().body(String.class);
        } catch (RestClientResponseException exception) { throw translate(model, connector, exception); }
        catch (RestClientException exception) {
            log.warn(
                    "Mistral sourcing request failed: model={}, errorType={}, causeType={}, timeoutSeconds={}",
                    model,
                    exception.getClass().getSimpleName(),
                    rootCauseType(exception),
                    timeoutSeconds);
            throw exception;
        }
    }
    private RuntimeException translate(String model, RestClientResponseException exception) {
        return translate(model, null, exception);
    }
    private RuntimeException translate(String model, String connector, RestClientResponseException exception) {
        log.warn("Mistral sourcing HTTP failed: model={}, status={}", model, exception.getStatusCode().value());
        if (isConnectorQuotaError(exception)) {
            log.warn("Mistral Custom Connector quota exhausted: connector={}",
                    connector == null ? "unavailable" : connector);
            return new SourcingConnectorQuotaExceededException(exception);
        }
        if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
            return new AiRateLimitException("Mistral API rate limit exceeded", exception);
        if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
            AiModelUnavailableEvent event = new AiModelUnavailableEvent(
                    "MODEL_REASONING", model, exception.getStatusCode().value());
            AiExecutionWarningContext.record(event);
            eventPublisher.publishEvent(event);
            return new AiModelNotFoundException(model, exception);
        }
        return exception;
    }
    private void rotateConnector(int attemptedIndex) {
        connectorCursor.compareAndSet(attemptedIndex, (attemptedIndex + 1) % connectors.size());
    }
    static boolean isConnectorQuotaError(RestClientResponseException exception) {
        if (exception.getStatusCode() != HttpStatus.BAD_REQUEST
                && exception.getStatusCode() != HttpStatus.UNPROCESSABLE_ENTITY
                && exception.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS) return false;
        String body = exception.getResponseBodyAsString().toLowerCase(Locale.ROOT);
        return body.contains("custom connector rate limit")
                || body.contains("custom connector quota")
                || body.contains("connector usage limit")
                || body.contains("connector quota");
    }
    private static List<String> parseConnectors(String configured) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (configured != null) Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).forEach(values::add);
        if (values.isEmpty()) throw new IllegalArgumentException("至少必須設定一個 Mistral sourcing connector");
        return List.copyOf(values);
    }
    private JsonNode parse(String raw, String model) {
        try { return mapper.readTree(raw); }
        catch (JsonProcessingException | IllegalArgumentException exception) {
            log.warn("Mistral sourcing JSON parse failed: model={}, errorType={}", model, exception.getClass().getSimpleName());
            throw new IllegalStateException("Mistral 回應不是有效 JSON", exception);
        }
    }
    private static String extractContent(JsonNode content) {
        if (content.isTextual() && !content.textValue().isBlank()) return content.textValue();
        if (content.isArray()) {
            StringBuilder value = new StringBuilder();
            content.forEach(part -> {
                if ("text".equals(part.path("type").asText()) && part.path("text").isTextual())
                    value.append(part.path("text").asText());
            });
            if (!value.isEmpty()) return value.toString();
        }
        throw new IllegalStateException("Mistral message.output 缺少文字 content");
    }
    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.intValue();
    }
    private static String rootCauseType(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName();
    }
}
