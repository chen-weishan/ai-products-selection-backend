package com.example.ssds.ai.agent;

import com.example.ssds.ai.access.tracka.AiClientResponse;
import com.example.ssds.ai.budget.AiBudgetExceededException;
import com.example.ssds.ai.access.common.AiModelNotFoundException;
import com.example.ssds.ai.access.tracka.AiPromptRequest;
import com.example.ssds.ai.resilience.AiRateLimitException;
import com.example.ssds.ai.resilience.RetryExecutionState;
import com.example.ssds.ai.resilience.RetrySleeper;
import com.example.ssds.ai.resilience.SafeLogMessage;
import com.example.ssds.ai.policy.ExternalLlmDisabledException;
import com.example.ssds.ai.policy.OutboundDataPolicyException;
import com.example.ssds.ai.config.MistralModelCatalog;
import com.example.ssds.ai.model.FallbackReason;
import com.example.ssds.ai.model.scene.*;
import com.example.ssds.ai.prompt.scene.SceneClassifierPromptFactory;
import com.example.ssds.ai.access.tracka.AiAccessRouter;
import com.example.ssds.ai.schema.AiSchemaValidationException;
import com.example.ssds.ai.schema.scene.SceneClassifierResponseParser;
import com.example.ssds.ai.schema.scene.SceneClassifierSchema;
import com.example.ssds.core.domain.AiTaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
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
            MistralModelCatalog modelCatalog,
            @Value("${ai.retry-max:3}") int retryMax,
            @Value("${ai.cache-days:6}") long cacheDays) {
        this(
                router,
                promptFactory,
                parser,
                objectMapper,
                modelCatalog.classify().primary(),
                modelCatalog.classify().fallbacks(),
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
        this.models = new MistralModelCatalog.ModelChain(primaryModel, fallbackModels).models();
        this.retryMax = Math.max(0, retryMax);
        this.retrySleeper = retrySleeper;
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofDays(cacheDays)).maximumSize(10_000).build();
    }

    public SceneClassifierAgent(
            AiAccessRouter router,
            SceneClassifierPromptFactory promptFactory,
            SceneClassifierResponseParser parser,
            ObjectMapper objectMapper,
            String primaryModel,
            String fallbackModels,
            int retryMax,
            long cacheDays) {
        this(router, promptFactory, parser, objectMapper, primaryModel, fallbackModels,
                retryMax, cacheDays, Thread::sleep);
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
        RetryExecutionState retry = new RetryExecutionState(models, retryMax);
        while (true) {
            String model = retry.model();
            String raw = null;
            try {
                log.info(
                        "SceneClassifier request: productId={}, modelAlias=MODEL_CLASSIFY, model={}, promptVersion={}",
                        input.productId(), model, SceneClassifierPromptFactory.PROMPT_VERSION);
                AiPromptRequest request = new AiPromptRequest(
                        AiTaskType.SCENE_CLASSIFY,
                        model,
                        promptFactory.systemPrompt()
                                + (retry.retryInstruction() == null ? "" : "\n\n" + retry.retryInstruction()),
                        promptFactory.userPrompt(input),
                        SceneClassifierSchema.create(objectMapper),
                        retry.isRetryAttempt());
                retry.recordRequest();
                AiClientResponse response = router.route(request);
                raw = response.content();
                SceneClassifierOutput output = parser.parse(raw, input);
                return output.confidence().compareTo(MIN_CONFIDENCE) < 0
                        ? fallback(
                                FallbackReason.LOW_CONFIDENCE,
                                output.confidence(),
                                output.reasoning(),
                                output.signals(),
                                raw,
                                response.model(),
                                response.promptTokens(),
                                response.completionTokens(),
                                retry.requestCount())
                        : new SceneClassificationResult(
                                output,
                                false,
                                null,
                                false,
                                raw,
                                response.model(),
                                SceneClassifierPromptFactory.PROMPT_VERSION,
                                response.promptTokens(),
                                response.completionTokens(),
                                retry.requestCount());
            } catch (AiSchemaValidationException exception) {
                log.warn(
                        "SceneClassifier schema validation failed: productId={}, model={}, errorType={}, reason={}",
                        input.productId(),
                        model,
                        exception.getClass().getSimpleName(),
                        SafeLogMessage.sanitize(exception.getMessage()));
                retry.retryInstruction(promptFactory.retryInstruction(validationCode(exception)));
                if (retry.beginSchemaRetry()) {
                    if (pauseBeforeRetry(2_000L, input.productId(), model)) continue;
                    return unavailableFallback(raw, model, retry.requestCount());
                }
                if (retry.moveToSchemaFallback()) continue;
                return fallback(
                        FallbackReason.SCHEMA_INVALID,
                        null,
                        "AI 回應格式驗證失敗",
                        List.of("fallback: schema_invalid"),
                        raw,
                        model,
                        null,
                        null,
                        retry.requestCount());
            } catch (AiRateLimitException exception) {
                OptionalLong delay = retry.nextRateLimitDelay();
                if (delay.isPresent()) {
                    long backoffMillis = delay.getAsLong();
                    log.warn(
                            "SceneClassifier rate limited; retrying same model: productId={}, model={}, retry={}/{}, backoffMs={}",
                            input.productId(),
                            model,
                            retry.rateLimitRetryCount(),
                            retry.rateLimitRetryMax(),
                            backoffMillis);
                    if (pauseBeforeRetry(backoffMillis, input.productId(), model)) continue;
                    return unavailableFallback(raw, model, retry.requestCount());
                }
                if (retry.moveToNextModel()) continue;
                return unavailableFallback(raw, model, retry.requestCount());
            } catch (AiModelNotFoundException exception) {
                log.warn("SceneClassifier model unavailable; switching fallback: productId={}, model={}",
                        input.productId(), model);
                if (retry.moveToNextModel()) continue;
                return unavailableFallback(raw, model, retry.requestCount());
            } catch (ResourceAccessException exception) {
                log.warn(
                        "SceneClassifier timed out; switching fallback: productId={}, model={}",
                        input.productId(), model);
                if (retry.moveToNextModel()) continue;
                return unavailableFallback(raw, model, retry.requestCount());
            } catch (ExternalLlmDisabledException | OutboundDataPolicyException exception) {
                log.warn("SceneClassifier external request blocked by policy: productId={}, reason={}",
                        input.productId(), SafeLogMessage.sanitize(exception.getMessage()));
                return unavailableFallback(raw, "policy-blocked", Math.max(0, retry.requestCount() - 1));
            } catch (AiBudgetExceededException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                log.warn(
                        "SceneClassifier AI request failed: productId={}, model={}, errorType={}",
                        input.productId(),
                        model,
                        exception.getClass().getSimpleName());
                if (retry.moveToNextModel()) continue;
                return unavailableFallback(raw, model, retry.requestCount());
            }
        }
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

    private static SceneClassificationResult unavailableFallback(String raw, String model, int requestCount) {
        return fallback(
                FallbackReason.AI_UNAVAILABLE,
                null,
                "AI 服務暫時無法使用",
                List.of("fallback: ai_unavailable"),
                raw,
                model,
                null,
                null,
                requestCount);
    }

    private static SceneClassificationResult fallback(
            FallbackReason reason,
            BigDecimal confidence,
            String reasoning,
            List<String> signals,
            String raw,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            int requestCount) {
        SceneClassifierOutput fallback = new SceneClassifierOutput(
                SceneCode.REPLENISHMENT,
                confidence,
                reasoning,
                null,
                signals);
        return new SceneClassificationResult(
                fallback,
                true,
                reason,
                false,
                raw,
                model,
                SceneClassifierPromptFactory.PROMPT_VERSION,
                promptTokens,
                completionTokens,
                requestCount);
    }

    private static Integer slopeBucket(BigDecimal slope7d) {
        if (slope7d == null) return null;
        return slope7d.multiply(BigDecimal.TEN)
                .setScale(0, RoundingMode.FLOOR)
                .intValueExact();
    }

    private static String validationCode(AiSchemaValidationException exception) {
        String message = exception.getMessage();
        if (message == null) return "SCHEMA_INVALID";
        if (isShapeError(message)) return "SHAPE_INVALID";
        if (message.contains("confidence")) return "CONFIDENCE_INVALID";
        if (message.contains("signals")) return "SIGNALS_INVALID";
        if (message.contains("數字")) return "NUMBER_NOT_IN_INPUT";
        if (message.contains("情境") || message.contains("scene")) return "SCENE_ENUM_INVALID";
        return "SCHEMA_INVALID";
    }

    private static boolean isShapeError(String message) {
        return message.contains("根物件") || message.contains("根節點")
                || message.contains("不得包含欄位") || message.contains("缺少欄位")
                || message.contains("欄位必須");
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

}
