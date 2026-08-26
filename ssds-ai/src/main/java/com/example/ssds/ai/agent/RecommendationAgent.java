package com.example.ssds.ai.agent;

import com.example.ssds.ai.client.*;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.RecommendationPromptFactory;
import com.example.ssds.ai.routing.AiAccessRouter;
import com.example.ssds.ai.schema.*;
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
            @Value("${mistral.model-short-gen-primary:mistral-small-latest}") String primaryModel,
            @Value("${mistral.model-short-gen-fallbacks:mistral-medium-latest,magistral-small-latest,magistral-medium-latest}") String fallbackModels,
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
        this.models = parseModels(primaryModel, fallbackModels);
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
        int modelIndex = 0;
        int rateLimitRetries = 0;
        int schemaRetries = 0;
        int serviceRetries = 0;
        int requestCount = 0;
        while (true) {
            String model = models.get(modelIndex);
            try {
                log.info(
                        "Recommendation request: productId={}, modelAlias=MODEL_SHORT_GEN, model={}, promptVersion={}",
                        input.productId(), model, RecommendationPromptFactory.PROMPT_VERSION);
                AiPromptRequest request = new AiPromptRequest(
                        AiTaskType.RECOMMENDATION,
                        model,
                        promptFactory.systemPrompt(),
                        promptFactory.userPrompt(input),
                        RecommendationSchema.create(objectMapper));
                requestCount++;
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
                        requestCount);
            } catch (AiSchemaValidationException exception) {
                log.warn(
                        "Recommendation schema validation failed: productId={}, model={}, reason={}",
                        input.productId(), model, safeLogMessage(exception.getMessage()));
                if (schemaRetries < 1 && hasNextModel(modelIndex)) {
                    schemaRetries++;
                    modelIndex++;
                    if (pause(2_000L, input.productId(), model)) continue;
                }
                return fallback(input, FallbackReason.SCHEMA_INVALID, model, requestCount);
            } catch (AiRateLimitException exception) {
                if (rateLimitRetries < retryMax && hasNextModel(modelIndex)) {
                    long backoffMillis = 1_000L << Math.min(rateLimitRetries, 10);
                    rateLimitRetries++;
                    modelIndex++;
                    log.warn(
                            "Recommendation rate limited; next model retry: productId={}, model={}, retry={}/{}, backoffMs={}",
                            input.productId(), model, rateLimitRetries, retryMax, backoffMillis);
                    if (pause(backoffMillis, input.productId(), model)) continue;
                }
                return fallback(input, FallbackReason.AI_UNAVAILABLE, model, requestCount);
            } catch (RuntimeException exception) {
                log.warn(
                        "Recommendation request failed: productId={}, model={}, errorType={}",
                        input.productId(), model, exception.getClass().getSimpleName());
                if (serviceRetries < 1 && hasNextModel(modelIndex)) {
                    serviceRetries++;
                    modelIndex++;
                    continue;
                }
                return fallback(input, FallbackReason.AI_UNAVAILABLE, model, requestCount);
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
                action = DecisionType.WATCH;
            }
        }
        String reasoning = switch (action) {
            case ADOPT -> "規則式預設建議：分級適合採納，數量限於後端允許範圍。";
            case WATCH -> "規則式預設建議：資料或分級仍需觀察，暫不建立首批數量。";
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

    private boolean hasNextModel(int index) {
        return index + 1 < models.size();
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

    private static List<String> parseModels(String primary, String fallbacks) {
        LinkedHashSet<String> configured = new LinkedHashSet<>();
        addModel(configured, primary);
        if (fallbacks != null) {
            for (String model : fallbacks.split(",")) addModel(configured, model);
        }
        if (configured.isEmpty()) throw new IllegalArgumentException("至少必須設定一個 Recommendation 模型");
        return List.copyOf(configured);
    }

    private static void addModel(LinkedHashSet<String> configured, String model) {
        if (model != null && !model.isBlank()) configured.add(model.trim());
    }

    private static String safeLogMessage(String message) {
        if (message == null) return "unavailable";
        String sanitized = message.replace('\r', ' ').replace('\n', ' ');
        return sanitized.length() <= 160 ? sanitized : sanitized.substring(0, 160);
    }

    private record CacheKey(
            Long productId,
            Grade grade,
            SceneType sceneType,
            int bonusBucket,
            BigDecimal penaltySubtotal,
            String promptVersion) {}

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
