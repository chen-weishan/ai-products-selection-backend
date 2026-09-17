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
import com.example.ssds.ai.model.insight.*;
import com.example.ssds.ai.prompt.insight.ProductInsightPromptFactory;
import com.example.ssds.ai.access.tracka.AiAccessRouter;
import com.example.ssds.ai.schema.AiSchemaValidationException;
import com.example.ssds.ai.schema.insight.ProductInsightResponseParser;
import com.example.ssds.ai.schema.insight.ProductInsightSchema;
import com.example.ssds.core.domain.AiTaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
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
        this.models = new MistralModelCatalog.ModelChain(primaryModel, fallbackModels).models();
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
        RetryExecutionState retry = new RetryExecutionState(models, retryMax);
        while (true) {
            String model = retry.model();
            try {
                log.info(
                        "ProductInsight request: productId={}, reviewCount={}, modelAlias=MODEL_LONG_TEXT, model={}, promptVersion={}",
                        input.productId(), input.reviews().size(), model, ProductInsightPromptFactory.PROMPT_VERSION);
                AiPromptRequest request = new AiPromptRequest(
                        AiTaskType.SELLING_POINT,
                        model,
                        promptFactory.systemPrompt()
                                + (retry.retryInstruction() == null ? "" : "\n\n" + retry.retryInstruction()),
                        promptFactory.userPrompt(input),
                        ProductInsightSchema.create(objectMapper),
                        retry.isRetryAttempt());
                retry.recordRequest();
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
                        retry.requestCount());
            } catch (AiSchemaValidationException exception) {
                log.warn(
                        "ProductInsight schema validation failed: productId={}, model={}, reason={}",
                        input.productId(), model, SafeLogMessage.sanitize(exception.getMessage()));
                retry.retryInstruction(promptFactory.retryInstruction(validationCode(exception)));
                if (retry.beginSchemaRetry()) {
                    if (pause(2_000L, input.productId(), model)) continue;
                }
                if (retry.moveToSchemaFallback()) continue;
                return fallback(FallbackReason.SCHEMA_INVALID, model, retry.requestCount());
            } catch (AiRateLimitException exception) {
                OptionalLong delay = retry.nextRateLimitDelay();
                if (delay.isPresent()) {
                    long backoffMillis = delay.getAsLong();
                    log.warn(
                            "ProductInsight rate limited; retrying same model: productId={}, model={}, retry={}/{}, backoffMs={}",
                            input.productId(), model, retry.rateLimitRetryCount(), retry.rateLimitRetryMax(), backoffMillis);
                    if (pause(backoffMillis, input.productId(), model)) continue;
                }
                if (retry.moveToNextModel()) continue;
                return fallback(FallbackReason.AI_UNAVAILABLE, model, retry.requestCount());
            } catch (AiModelNotFoundException exception) {
                log.warn("ProductInsight model unavailable; switching fallback: productId={}, model={}",
                        input.productId(), model);
                if (retry.moveToNextModel()) continue;
                return fallback(FallbackReason.AI_UNAVAILABLE, model, retry.requestCount());
            } catch (ResourceAccessException exception) {
                log.warn(
                        "ProductInsight timed out; switching fallback: productId={}, model={}",
                        input.productId(), model);
                if (retry.moveToNextModel()) continue;
                return fallback(FallbackReason.AI_UNAVAILABLE, model, retry.requestCount());
            } catch (ExternalLlmDisabledException | OutboundDataPolicyException exception) {
                log.warn("ProductInsight external request blocked by policy: productId={}, reason={}",
                        input.productId(), SafeLogMessage.sanitize(exception.getMessage()));
                return fallback(FallbackReason.AI_UNAVAILABLE, "policy-blocked", Math.max(0, retry.requestCount() - 1));
            } catch (AiBudgetExceededException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                log.warn(
                        "ProductInsight request failed: productId={}, model={}, errorType={}",
                        input.productId(), model, exception.getClass().getSimpleName());
                if (retry.moveToNextModel()) continue;
                return fallback(FallbackReason.AI_UNAVAILABLE, model, retry.requestCount());
            }
        }
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

    private static String validationCode(AiSchemaValidationException exception) {
        String message = exception.getMessage();
        if (message == null) return "SCHEMA_INVALID";
        if (isShapeError(message)) return "SHAPE_INVALID";
        if (message.contains("supportCount") || message.contains("資料不足")) return "SUPPORT_INVALID";
        if (message.contains("countedInPenalty")) return "PENALTY_MAPPING_INVALID";
        if (message.contains("數字")) return "NUMBER_NOT_IN_INPUT";
        if (message.contains("sellingPoints") || message.contains("risks")) return "ARRAY_INVALID";
        return "SCHEMA_INVALID";
    }

    private static boolean isShapeError(String message) {
        return message.contains("根物件") || message.contains("根節點")
                || message.contains("不得包含欄位") || message.contains("缺少欄位")
                || message.contains("欄位必須");
    }

    private record CacheKey(
            Long productId,
            int reviewCountBucket,
            LocalDate latestReviewDate,
            String promptVersion) {}

}
