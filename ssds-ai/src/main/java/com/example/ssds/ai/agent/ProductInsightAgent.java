package com.example.ssds.ai.agent;

import com.example.ssds.ai.access.common.AiModelNotFoundException;
import com.example.ssds.ai.access.tracka.AiClientResponse;
import com.example.ssds.ai.access.tracka.AiPromptRequest;
import com.example.ssds.ai.budget.AiBudgetExceededException;
import com.example.ssds.ai.policy.ExternalLlmDisabledException;
import com.example.ssds.ai.policy.OutboundDataPolicyException;
import com.example.ssds.ai.resilience.AiRateLimitException;
import com.example.ssds.ai.config.MistralModelCatalog;
import com.example.ssds.ai.model.FallbackReason;
import com.example.ssds.ai.model.insight.*;
import com.example.ssds.ai.prompt.ProductInsightPromptFactory;
import com.example.ssds.ai.access.tracka.AiAccessRouter;
import com.example.ssds.ai.schema.*;
import com.example.ssds.core.domain.AiTaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

@Component
public class ProductInsightAgent {
    private static final Logger log = LoggerFactory.getLogger(ProductInsightAgent.class);
    private final AiAccessRouter router;
    private final ProductInsightPromptFactory promptFactory;
    private final ProductInsightResponseParser parser;
    private final ObjectMapper objectMapper;
    private final List<String> models;
    private final int retryMax;
    private final RetrySleeper retrySleeper;
    private final Cache<CacheKey, ProductInsightResult> cache;

    @Autowired
    public ProductInsightAgent(
            AiAccessRouter router,
            ProductInsightPromptFactory promptFactory,
            ProductInsightResponseParser parser,
            ObjectMapper objectMapper,
            MistralModelCatalog modelCatalog,
            @Value("${ai.retry-max:3}") int retryMax,
            @Value("${ai.cache-days:6}") long cacheDays) {
        this(
                router,
                promptFactory,
                parser,
                objectMapper,
                modelCatalog.longText().primary(),
                modelCatalog.longText().fallbacks(),
                retryMax,
                cacheDays,
                Thread::sleep);
    }

    ProductInsightAgent(
            AiAccessRouter router,
            ProductInsightPromptFactory promptFactory,
            ProductInsightResponseParser parser,
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

    public ProductInsightResult analyze(
            ProductInsightInput input,
            int reviewCountBucket,
            LocalDate latestReviewDate,
            boolean forceRefresh) {
        CacheKey key = new CacheKey(
                input.productId(),
                reviewCountBucket,
                latestReviewDate,
                ProductInsightPromptFactory.PROMPT_VERSION);
        if (!forceRefresh) {
            ProductInsightResult cached = cache.getIfPresent(key);
            if (cached != null) return cached.asCacheHit();
        }
        if (input.reviews().isEmpty()) {
            ProductInsightResult noReviews = new ProductInsightResult(
                    new ProductInsightOutput(List.of(), List.of()),
                    false,
                    null,
                    false,
                    "not-invoked",
                    ProductInsightPromptFactory.PROMPT_VERSION,
                    null,
                    null,
                    0);
            cache.put(key, noReviews);
            return noReviews;
        }

        ProductInsightResult result = analyzeWithRetry(input);
        if (!result.fallbackApplied()) cache.put(key, result);
        return result;
    }

    private ProductInsightResult analyzeWithRetry(ProductInsightInput input) {
        int modelIndex = 0;
        int rateLimitRetries = 0;
        int schemaRetries = 0;
        int requestCount = 0;
        while (true) {
            String model = models.get(modelIndex);
            try {
                log.info(
                        "ProductInsight request: productId={}, reviewCount={}, modelAlias=MODEL_LONG_TEXT, model={}, promptVersion={}",
                        input.productId(), input.reviews().size(), model, ProductInsightPromptFactory.PROMPT_VERSION);
                AiPromptRequest request = new AiPromptRequest(
                        AiTaskType.SELLING_POINT,
                        model,
                        promptFactory.systemPrompt(),
                        promptFactory.userPrompt(input),
                        ProductInsightSchema.create(objectMapper),
                        requestCount > 0);
                requestCount++;
                AiClientResponse response = router.route(request);
                ProductInsightOutput output = parser.parse(response.content(), input);
                return new ProductInsightResult(
                        output,
                        false,
                        null,
                        false,
                        response.model(),
                        ProductInsightPromptFactory.PROMPT_VERSION,
                        response.promptTokens(),
                        response.completionTokens(),
                        requestCount);
            } catch (AiSchemaValidationException exception) {
                log.warn(
                        "ProductInsight schema validation failed: productId={}, model={}, reason={}",
                        input.productId(), model, safeLogMessage(exception.getMessage()));
                if (schemaRetries == 0) {
                    schemaRetries++;
                    if (pause(2_000L, input.productId(), model)) continue;
                }
                if (schemaRetries == 1 && hasNextModel(modelIndex)) {
                    schemaRetries++;
                    modelIndex++;
                    continue;
                }
                return fallback(FallbackReason.SCHEMA_INVALID, model, requestCount);
            } catch (AiRateLimitException exception) {
                if (rateLimitRetries < retryMax) {
                    long backoffMillis = 1_000L << Math.min(rateLimitRetries, 10);
                    rateLimitRetries++;
                    log.warn(
                            "ProductInsight rate limited; retrying same model: productId={}, model={}, retry={}/{}, backoffMs={}",
                            input.productId(), model, rateLimitRetries, retryMax, backoffMillis);
                    if (pause(backoffMillis, input.productId(), model)) continue;
                }
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return fallback(FallbackReason.AI_UNAVAILABLE, model, requestCount);
            } catch (AiModelNotFoundException exception) {
                log.warn("ProductInsight model unavailable; switching fallback: productId={}, model={}",
                        input.productId(), model);
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return fallback(FallbackReason.AI_UNAVAILABLE, model, requestCount);
            } catch (ResourceAccessException exception) {
                log.warn(
                        "ProductInsight timed out; switching fallback: productId={}, model={}",
                        input.productId(), model);
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return fallback(FallbackReason.AI_UNAVAILABLE, model, requestCount);
            } catch (ExternalLlmDisabledException | OutboundDataPolicyException exception) {
                log.warn("ProductInsight external request blocked by policy: productId={}, reason={}",
                        input.productId(), safeLogMessage(exception.getMessage()));
                return fallback(FallbackReason.AI_UNAVAILABLE, "policy-blocked", Math.max(0, requestCount - 1));
            } catch (AiBudgetExceededException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                log.warn(
                        "ProductInsight request failed: productId={}, model={}, errorType={}",
                        input.productId(), model, exception.getClass().getSimpleName());
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return fallback(FallbackReason.AI_UNAVAILABLE, model, requestCount);
            }
        }
    }

    private boolean hasNextModel(int modelIndex) {
        return modelIndex + 1 < models.size();
    }

    private boolean pause(long millis, Long productId, String model) {
        try {
            retrySleeper.sleep(millis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("ProductInsight retry interrupted: productId={}, model={}", productId, model);
            return false;
        }
    }

    private static ProductInsightResult fallback(
            FallbackReason reason, String model, int requestCount) {
        return new ProductInsightResult(
                new ProductInsightOutput(List.of(), List.of()),
                true,
                reason,
                false,
                model,
                ProductInsightPromptFactory.PROMPT_VERSION,
                null,
                null,
                requestCount);
    }

    private static List<String> parseModels(String primary, String fallbacks) {
        LinkedHashSet<String> configured = new LinkedHashSet<>();
        addModel(configured, primary);
        if (fallbacks != null) {
            for (String model : fallbacks.split(",")) addModel(configured, model);
        }
        if (configured.isEmpty()) throw new IllegalArgumentException("至少必須設定一個 ProductInsight 模型");
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
            int reviewCountBucket,
            LocalDate latestReviewDate,
            String promptVersion) {}

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
