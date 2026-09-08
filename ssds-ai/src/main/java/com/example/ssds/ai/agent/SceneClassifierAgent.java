package com.example.ssds.ai.agent;

import com.example.ssds.ai.client.AiClientResponse;
import com.example.ssds.ai.client.AiBudgetExceededException;
import com.example.ssds.ai.client.AiModelNotFoundException;
import com.example.ssds.ai.client.AiPromptRequest;
import com.example.ssds.ai.client.AiRateLimitException;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.SceneClassifierPromptFactory;
import com.example.ssds.ai.routing.AiAccessRouter;
import com.example.ssds.ai.schema.AiSchemaValidationException;
import com.example.ssds.ai.schema.SceneClassifierResponseParser;
import com.example.ssds.ai.schema.SceneClassifierSchema;
import com.example.ssds.core.domain.AiTaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

@Component
public class SceneClassifierAgent {
    private static final Logger log = LoggerFactory.getLogger(SceneClassifierAgent.class);
    private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.5");
    private final AiAccessRouter router;
    private final SceneClassifierPromptFactory promptFactory;
    private final SceneClassifierResponseParser parser;
    private final ObjectMapper objectMapper;
    private final List<String> models;
    private final int retryMax;
    private final RetrySleeper retrySleeper;
    private final Cache<CacheKey, SceneClassificationResult> cache;

    @Autowired
    public SceneClassifierAgent(
            AiAccessRouter router,
            SceneClassifierPromptFactory promptFactory,
            SceneClassifierResponseParser parser,
            ObjectMapper objectMapper,
            @Value("${mistral.model-classify-primary:mistral-medium-3-5}") String primaryModel,
            @Value("${mistral.model-classify-fallbacks:mistral-small-latest,magistral-medium-latest}") String fallbackModels,
            @Value("${ai.retry-max:3}") int retryMax,
            @Value("${ai.cache-days:6}") long cacheDays) {
        this(
                router,
                promptFactory,
                parser,
                objectMapper,
                primaryModel,
                fallbackModels,
                retryMax,
                cacheDays,
                Thread::sleep);
    }

    SceneClassifierAgent(
            AiAccessRouter router,
            SceneClassifierPromptFactory promptFactory,
            SceneClassifierResponseParser parser,
            ObjectMapper objectMapper,
            String primaryModel,
            String fallbackModels,
            int retryMax,
            long cacheDays,
            RetrySleeper retrySleeper) {
        this.router = router;
        this.promptFactory = promptFactory;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.models = parseModels(primaryModel, fallbackModels);
        this.retryMax = Math.max(0, retryMax);
        this.retrySleeper = retrySleeper;
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofDays(cacheDays)).maximumSize(10_000).build();
    }

    public SceneClassificationResult classify(SceneClassifierInput input, boolean forceRefresh) {
        CacheKey key = new CacheKey(
                input.productId(),
                input.heatStage(),
                slopeBucket(input.heatSlope7d()),
                festivalFingerprint(input.festivalMatches()),
                SceneClassifierPromptFactory.PROMPT_VERSION);
        if (!forceRefresh) {
            SceneClassificationResult cached = cache.getIfPresent(key);
            if (cached != null) return cached.asCacheHit();
        }

        SceneClassificationResult result = classifyWithRetry(input);

        if (result.fallbackReason() == null || result.fallbackReason() == FallbackReason.LOW_CONFIDENCE) {
            cache.put(key, result);
        }
        return result;
    }

    private SceneClassificationResult classifyWithRetry(SceneClassifierInput input) {
        int modelIndex = 0;
        int rateLimitRetries = 0;
        int schemaRetries = 0;
        int requestCount = 0;
        while (true) {
            String model = models.get(modelIndex);
            String raw = null;
            try {
                log.info(
                        "SceneClassifier request: productId={}, modelAlias=MODEL_CLASSIFY, model={}, promptVersion={}",
                        input.productId(), model, SceneClassifierPromptFactory.PROMPT_VERSION);
                AiPromptRequest request = new AiPromptRequest(
                        AiTaskType.SCENE_CLASSIFY,
                        model,
                        promptFactory.systemPrompt(),
                        promptFactory.userPrompt(input),
                        SceneClassifierSchema.create(objectMapper),
                        requestCount > 0);
                requestCount++;
                AiClientResponse response = router.route(request);
                raw = response.content();
                SceneClassifierOutput output = parser.parse(raw);
                return output.confidence().compareTo(MIN_CONFIDENCE) < 0
                        ? fallback(
                                FallbackReason.LOW_CONFIDENCE,
                                output.confidence(),
                                output.reasoning(),
                                output.signals(),
                                raw,
                                response.model())
                        : new SceneClassificationResult(
                                output,
                                false,
                                null,
                                false,
                                raw,
                                response.model(),
                                SceneClassifierPromptFactory.PROMPT_VERSION);
            } catch (AiSchemaValidationException exception) {
                log.warn(
                        "SceneClassifier schema validation failed: productId={}, model={}, errorType={}, reason={}",
                        input.productId(),
                        model,
                        exception.getClass().getSimpleName(),
                        safeLogMessage(exception.getMessage()));
                if (schemaRetries == 0) {
                    schemaRetries++;
                    if (pauseBeforeRetry(2_000L, input.productId(), model)) continue;
                    return unavailableFallback(raw, model);
                }
                if (schemaRetries == 1 && hasNextModel(modelIndex)) {
                    schemaRetries++;
                    modelIndex++;
                    continue;
                }
                return fallback(
                        FallbackReason.SCHEMA_INVALID,
                        null,
                        "AI 回應格式驗證失敗",
                        List.of("fallback: schema_invalid"),
                        raw,
                        model);
            } catch (AiRateLimitException exception) {
                if (rateLimitRetries < retryMax) {
                    long backoffMillis = 1_000L << Math.min(rateLimitRetries, 10);
                    rateLimitRetries++;
                    log.warn(
                            "SceneClassifier rate limited; retrying same model: productId={}, model={}, retry={}/{}, backoffMs={}",
                            input.productId(),
                            model,
                            rateLimitRetries,
                            retryMax,
                            backoffMillis);
                    if (pauseBeforeRetry(backoffMillis, input.productId(), model)) continue;
                    return unavailableFallback(raw, model);
                }
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return unavailableFallback(raw, model);
            } catch (AiModelNotFoundException exception) {
                log.warn("SceneClassifier model unavailable; switching fallback: productId={}, model={}",
                        input.productId(), model);
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return unavailableFallback(raw, model);
            } catch (ResourceAccessException exception) {
                log.warn(
                        "SceneClassifier timed out; switching fallback: productId={}, model={}",
                        input.productId(), model);
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return unavailableFallback(raw, model);
            } catch (AiBudgetExceededException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                log.warn(
                        "SceneClassifier AI request failed: productId={}, model={}, errorType={}",
                        input.productId(),
                        model,
                        exception.getClass().getSimpleName());
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return unavailableFallback(raw, model);
            }
        }
    }

    private boolean hasNextModel(int modelIndex) {
        return modelIndex + 1 < models.size();
    }

    private boolean pauseBeforeRetry(long delayMillis, Long productId, String model) {
        try {
            retrySleeper.sleep(delayMillis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("SceneClassifier retry interrupted: productId={}, model={}", productId, model);
            return false;
        }
    }

    private static SceneClassificationResult unavailableFallback(String raw, String model) {
        return fallback(
                FallbackReason.AI_UNAVAILABLE,
                null,
                "AI 服務暫時無法使用",
                List.of("fallback: ai_unavailable"),
                raw,
                model);
    }

    private static List<String> parseModels(String primaryModel, String fallbackModels) {
        LinkedHashSet<String> configured = new LinkedHashSet<>();
        addModel(configured, primaryModel);
        if (fallbackModels != null) {
            for (String model : fallbackModels.split(",")) addModel(configured, model);
        }
        if (configured.isEmpty()) {
            throw new IllegalArgumentException("至少必須設定一個 Mistral SceneClassifier 模型");
        }
        return List.copyOf(configured);
    }

    private static void addModel(LinkedHashSet<String> configured, String model) {
        if (model != null && !model.isBlank()) configured.add(model.trim());
    }

    private static SceneClassificationResult fallback(
            FallbackReason reason,
            BigDecimal confidence,
            String reasoning,
            List<String> signals,
            String raw,
            String model) {
        SceneClassifierOutput fallback = new SceneClassifierOutput(
                SceneCode.REPLENISHMENT,
                confidence,
                reasoning,
                null,
                signals);
        return new SceneClassificationResult(
                fallback, true, reason, false, raw, model, SceneClassifierPromptFactory.PROMPT_VERSION);
    }

    private static String safeLogMessage(String message) {
        if (message == null) return "unavailable";
        String sanitized = message.replace('\r', ' ').replace('\n', ' ');
        return sanitized.length() <= 160 ? sanitized : sanitized.substring(0, 160);
    }

    private static Integer slopeBucket(BigDecimal slope7d) {
        if (slope7d == null) return null;
        return slope7d.multiply(BigDecimal.TEN)
                .setScale(0, RoundingMode.FLOOR)
                .intValueExact();
    }

    private static List<FestivalKey> festivalFingerprint(List<FestivalMatch> matches) {
        return matches.stream()
                .map(value -> new FestivalKey(
                        value.festivalCode(),
                        value.affinity() == null ? null : value.affinity().stripTrailingZeros()))
                .sorted(Comparator
                        .comparing(FestivalKey::festivalCode, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(FestivalKey::affinity, Comparator.nullsFirst(BigDecimal::compareTo)))
                .toList();
    }

    private record FestivalKey(String festivalCode, BigDecimal affinity) {}

    private record CacheKey(
            Long productId,
            com.example.ssds.core.domain.HeatStage heatStage,
            Integer slope7dBucket,
            List<FestivalKey> festivalMatches,
            String promptVersion) {}

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
