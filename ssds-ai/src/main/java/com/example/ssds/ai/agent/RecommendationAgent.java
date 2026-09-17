package com.example.ssds.ai.agent;

import com.example.ssds.ai.access.common.AiModelNotFoundException;
import com.example.ssds.ai.access.tracka.AiClientResponse;
import com.example.ssds.ai.access.tracka.AiPromptRequest;
import com.example.ssds.ai.budget.AiBudgetExceededException;
import com.example.ssds.ai.policy.ExternalLlmDisabledException;
import com.example.ssds.ai.policy.OutboundDataPolicyException;
import com.example.ssds.ai.resilience.AiRateLimitException;
import com.example.ssds.ai.resilience.RetryExecutionState;
import com.example.ssds.ai.resilience.RetrySleeper;
import com.example.ssds.ai.resilience.SafeLogMessage;
import com.example.ssds.ai.config.MistralModelCatalog;
import com.example.ssds.ai.model.FallbackReason;
import com.example.ssds.ai.model.recommendation.*;
import com.example.ssds.ai.prompt.recommendation.RecommendationPromptFactory;
import com.example.ssds.ai.access.tracka.AiAccessRouter;
import com.example.ssds.ai.schema.AiSchemaValidationException;
import com.example.ssds.ai.schema.recommendation.RecommendationResponseParser;
import com.example.ssds.ai.schema.recommendation.RecommendationSchema;
import com.example.ssds.core.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

@Component
public class RecommendationAgent {
    private static final Logger log = LoggerFactory.getLogger(RecommendationAgent.class);
    private static final EnumSet<FactorCode> REQUIRED_FACTORS = EnumSet.of(
            FactorCode.TREND,
            FactorCode.MARGIN,
            FactorCode.CVR,
            FactorCode.PRICE_FIT,
            FactorCode.FESTIVAL,
            FactorCode.CLIMATE);

    private final AiAccessRouter router;
    private final RecommendationPromptFactory promptFactory;
    private final RecommendationResponseParser parser;
    private final ObjectMapper objectMapper;
    private final List<String> models;
    private final int retryMax;
    private final RetrySleeper retrySleeper;
    private final Cache<CacheKey, RecommendationResult> cache;

    @Autowired
    public RecommendationAgent(
            AiAccessRouter router,
            RecommendationPromptFactory promptFactory,
            RecommendationResponseParser parser,
            ObjectMapper objectMapper,
            MistralModelCatalog modelCatalog,
            @Value("${ai.retry-max:3}") int retryMax,
            @Value("${ai.cache-days:6}") long cacheDays) {
        this(
                router,
                promptFactory,
                parser,
                objectMapper,
                modelCatalog.shortGeneration().primary(),
                modelCatalog.shortGeneration().fallbacks(),
                retryMax,
                cacheDays,
                Thread::sleep);
    }

    RecommendationAgent(
            AiAccessRouter router,
            RecommendationPromptFactory promptFactory,
            RecommendationResponseParser parser,
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
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofDays(cacheDays))
                .maximumSize(10_000)
                .build();
    }

    public RecommendationResult recommend(RecommendationInput input, boolean forceRefresh) {
        CacheKey key = new CacheKey(
                input.productId(),
                input.grade(),
                input.sceneType(),
                bonusBucket(input.bonusSubtotal()),
                input.penaltySubtotal(),
                RecommendationPromptFactory.PROMPT_VERSION);
        if (!forceRefresh) {
            RecommendationResult cached = cache.getIfPresent(key);
            if (cached != null) return cached.asCacheHit();
        }
        if (!hasSixFactors(input)) {
            return fallback(input, FallbackReason.DATA_INSUFFICIENT, "not-invoked", 0);
        }
        RecommendationResult result = recommendWithRetry(input);
        if (!result.fallbackApplied()) cache.put(key, result);
        return result;
    }

    private RecommendationResult recommendWithRetry(RecommendationInput input) {
        RetryExecutionState retry = new RetryExecutionState(models, retryMax);
        while (true) {
            String model = retry.model();
            try {
                log.info(
                        "Recommendation request: productId={}, modelAlias=MODEL_SHORT_GEN, model={}, promptVersion={}",
                        input.productId(), model, RecommendationPromptFactory.PROMPT_VERSION);
                AiPromptRequest request = new AiPromptRequest(
                        AiTaskType.RECOMMENDATION,
                        model,
                        promptFactory.systemPrompt()
                                + (retry.retryInstruction() == null ? "" : "\n\n" + retry.retryInstruction()),
                        promptFactory.userPrompt(input),
                        RecommendationSchema.create(objectMapper),
                        retry.isRetryAttempt());
                retry.recordRequest();
                AiClientResponse response = router.route(request);
                RecommendationOutput output = parser.parse(response.content(), input);
                return new RecommendationResult(
                        output,
                        false,
                        null,
                        false,
                        response.model(),
                        RecommendationPromptFactory.PROMPT_VERSION,
                        response.promptTokens(),
                        response.completionTokens(),
                        retry.requestCount());
            } catch (AiSchemaValidationException exception) {
                log.warn(
                        "Recommendation schema validation failed: productId={}, model={}, reason={}",
                        input.productId(), model, SafeLogMessage.sanitize(exception.getMessage()));
                retry.retryInstruction(promptFactory.retryInstruction(validationCode(exception)));
                if (retry.beginSchemaRetry()) {
                    if (pause(2_000L, input.productId(), model)) continue;
                }
                if (retry.moveToSchemaFallback()) continue;
                return fallback(input, FallbackReason.SCHEMA_INVALID, model, retry.requestCount());
            } catch (AiRateLimitException exception) {
                OptionalLong delay = retry.nextRateLimitDelay();
                if (delay.isPresent()) {
                    long backoffMillis = delay.getAsLong();
                    log.warn(
                            "Recommendation rate limited; retrying same model: productId={}, model={}, retry={}/{}, backoffMs={}",
                            input.productId(), model, retry.rateLimitRetryCount(), retry.rateLimitRetryMax(), backoffMillis);
                    if (pause(backoffMillis, input.productId(), model)) continue;
                }
                if (retry.moveToNextModel()) continue;
                return fallback(input, FallbackReason.AI_UNAVAILABLE, model, retry.requestCount());
            } catch (AiModelNotFoundException exception) {
                log.warn("Recommendation model unavailable; switching fallback: productId={}, model={}",
                        input.productId(), model);
                if (retry.moveToNextModel()) continue;
                return fallback(input, FallbackReason.AI_UNAVAILABLE, model, retry.requestCount());
            } catch (ResourceAccessException exception) {
                log.warn(
                        "Recommendation timed out; switching fallback: productId={}, model={}",
                        input.productId(), model);
                if (retry.moveToNextModel()) continue;
                return fallback(input, FallbackReason.AI_UNAVAILABLE, model, retry.requestCount());
            } catch (ExternalLlmDisabledException | OutboundDataPolicyException exception) {
                log.warn("Recommendation external request blocked by policy: productId={}, reason={}",
                        input.productId(), SafeLogMessage.sanitize(exception.getMessage()));
                return fallback(input, FallbackReason.AI_UNAVAILABLE, "policy-blocked",
                        Math.max(0, retry.requestCount() - 1));
            } catch (AiBudgetExceededException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                log.warn(
                        "Recommendation request failed: productId={}, model={}, errorType={}",
                        input.productId(), model, exception.getClass().getSimpleName());
                if (retry.moveToNextModel()) continue;
                return fallback(input, FallbackReason.AI_UNAVAILABLE, model, retry.requestCount());
            }
        }
    }

    private static RecommendationResult fallback(
            RecommendationInput input,
            FallbackReason reason,
            String model,
            int requestCount) {
        DecisionType action;
        if (input.penaltySubtotal() != null
                && input.penaltySubtotal().compareTo(BigDecimal.valueOf(20)) >= 0) {
            action = DecisionType.REJECT;
        } else if (input.grade() == Grade.A) {
            action = DecisionType.ADOPT;
        } else {
            action = DecisionType.WATCH;
        }

        int qtyMin = 0;
        int qtyMax = 0;
        String quantityText = "暫不建議進貨";
        if (action == DecisionType.ADOPT) {
            List<Integer> positive = input.allowedQuantities().stream()
                    .filter(value -> value != null && value > 0)
                    .sorted()
                    .toList();
            if (!positive.isEmpty()) {
                qtyMin = positive.getFirst();
                qtyMax = positive.getLast();
                quantityText = qtyMin == qtyMax
                        ? "建議首批 " + qtyMin + " 件"
                        : "建議首批 " + qtyMin + "–" + qtyMax + " 件";
            } else {
                quantityText = "建議採納，首批數量需人工確認";
            }
        }
        String reasoning = switch (action) {
            case ADOPT -> "規則式預設建議：分級達採納條件且扣分未達風險抑制門檻，建議採納。";
            case WATCH -> "規則式預設建議：分級尚未達採納條件且扣分未達淘汰條件，建議持續觀察。";
            case REJECT -> "規則式預設建議：扣分已達風險抑制條件，不建議進貨。";
        };
        return new RecommendationResult(
                new RecommendationOutput(action, qtyMin, qtyMax, quantityText, reasoning),
                true,
                reason,
                false,
                model,
                RecommendationPromptFactory.PROMPT_VERSION,
                null,
                null,
                requestCount);
    }

    private static boolean hasSixFactors(RecommendationInput input) {
        EnumSet<FactorCode> actual = EnumSet.noneOf(FactorCode.class);
        input.factors().forEach(value -> actual.add(value.factorCode()));
        return actual.equals(REQUIRED_FACTORS) && input.factors().size() == REQUIRED_FACTORS.size();
    }

    private static int bonusBucket(BigDecimal bonusSubtotal) {
        if (bonusSubtotal == null) return -1;
        return bonusSubtotal.divide(BigDecimal.valueOf(5), 0, RoundingMode.FLOOR).intValue();
    }

    private boolean pause(long millis, Long productId, String model) {
        try {
            retrySleeper.sleep(millis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Recommendation retry interrupted: productId={}, model={}", productId, model);
            return false;
        }
    }

    private static String validationCode(AiSchemaValidationException exception) {
        String message = exception.getMessage();
        if (message == null) return "SCHEMA_INVALID";
        if (isShapeError(message)) return "SHAPE_INVALID";
        if (message.contains("qty") || message.contains("allowedQuantities")
                || message.contains("quantityText") || message.contains("REJECT")) {
            return "QUANTITY_INVALID";
        }
        if (message.contains("數字")) return "NUMBER_NOT_IN_INPUT";
        if (message.contains("action")) return "ACTION_INVALID";
        return "SCHEMA_INVALID";
    }

    private static boolean isShapeError(String message) {
        return message.contains("根物件") || message.contains("根節點")
                || message.contains("不得包含欄位") || message.contains("缺少欄位")
                || message.contains("欄位必須");
    }

    private record CacheKey(
            Long productId,
            Grade grade,
            SceneType sceneType,
            int bonusBucket,
            BigDecimal penaltySubtotal,
            String promptVersion) {}

}
