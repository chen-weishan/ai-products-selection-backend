package com.example.ssds.ai.agent;

import com.example.ssds.ai.client.*;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.ReviewRiskPromptFactory;
import com.example.ssds.ai.routing.AiAccessRouter;
import com.example.ssds.ai.schema.*;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.ReviewRiskTopic;
import com.example.ssds.core.domain.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

@Component
public class ReviewRiskAgent {
    private static final Logger log = LoggerFactory.getLogger(ReviewRiskAgent.class);
    private final AiAccessRouter router;
    private final ReviewRiskPromptFactory promptFactory;
    private final ReviewRiskResponseParser parser;
    private final ObjectMapper objectMapper;
    private final List<String> models;
    private final int retryMax;
    private final RetrySleeper retrySleeper;
    private final Cache<CacheKey, ReviewRiskResult> cache;

    @Autowired
    public ReviewRiskAgent(
            AiAccessRouter router,
            ReviewRiskPromptFactory promptFactory,
            ReviewRiskResponseParser parser,
            ObjectMapper objectMapper,
            @Value("${mistral.model-long-text-primary:mistral-medium-3-5}") String primaryModel,
            @Value("${mistral.model-long-text-fallbacks:mistral-small-latest}") String fallbackModels,
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

    ReviewRiskAgent(
            AiAccessRouter router,
            ReviewRiskPromptFactory promptFactory,
            ReviewRiskResponseParser parser,
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

    public ReviewRiskResult analyze(
            ReviewRiskInput input, long totalReviewCount, Long latestReviewId, boolean forceRefresh) {
        CacheKey key = new CacheKey(
                input.productId(), totalReviewCount, latestReviewId, ReviewRiskPromptFactory.PROMPT_VERSION);
        if (!forceRefresh) {
            ReviewRiskResult cached = cache.getIfPresent(key);
            if (cached != null) return cached.asCacheHit();
        }
        if (input.reviews().isEmpty()) {
            ReviewRiskResult noReviews = new ReviewRiskResult(
                    new ReviewRiskOutput(List.of(), zeroStatistics()),
                    false,
                    null,
                    false,
                    "not-invoked",
                    ReviewRiskPromptFactory.PROMPT_VERSION);
            cache.put(key, noReviews);
            return noReviews;
        }

        ReviewRiskResult result = analyzeWithRetry(input);
        if (!result.fallbackApplied()) cache.put(key, result);
        return result;
    }

    private ReviewRiskResult analyzeWithRetry(ReviewRiskInput input) {
        int modelIndex = 0;
        int rateLimitRetries = 0;
        int schemaRetries = 0;
        while (true) {
            String model = models.get(modelIndex);
            try {
                log.info(
                        "ReviewRisk request: productId={}, reviewCount={}, modelAlias=MODEL_LONG_TEXT, model={}, promptVersion={}",
                        input.productId(), input.reviews().size(), model, ReviewRiskPromptFactory.PROMPT_VERSION);
                AiPromptRequest request = new AiPromptRequest(
                        AiTaskType.REVIEW_RISK,
                        model,
                        promptFactory.systemPrompt(),
                        promptFactory.userPrompt(input),
                        ReviewRiskSchema.create(objectMapper));
                AiClientResponse response = router.route(request);
                ReviewRiskOutput output = parser.parse(response.content(), input);
                return new ReviewRiskResult(
                        output,
                        false,
                        null,
                        false,
                        response.model(),
                        ReviewRiskPromptFactory.PROMPT_VERSION);
            } catch (AiSchemaValidationException exception) {
                log.warn(
                        "ReviewRisk schema validation failed: productId={}, model={}, errorType={}, reason={}",
                        input.productId(), model, exception.getClass().getSimpleName(), safeLogMessage(exception.getMessage()));
                if (schemaRetries == 0) {
                    schemaRetries++;
                    if (pauseBeforeRetry(2_000L, input.productId(), model)) continue;
                }
                if (schemaRetries == 1 && hasNextModel(modelIndex)) {
                    schemaRetries++;
                    modelIndex++;
                    continue;
                }
                return fallback(FallbackReason.SCHEMA_INVALID, model);
            } catch (AiRateLimitException exception) {
                if (rateLimitRetries < retryMax) {
                    long backoffMillis = 1_000L << Math.min(rateLimitRetries, 10);
                    rateLimitRetries++;
                    log.warn(
                            "ReviewRisk rate limited; retrying same model: productId={}, model={}, retry={}/{}, backoffMs={}",
                            input.productId(), model, rateLimitRetries, retryMax, backoffMillis);
                    if (pauseBeforeRetry(backoffMillis, input.productId(), model)) continue;
                }
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return fallback(FallbackReason.AI_UNAVAILABLE, model);
            } catch (AiModelNotFoundException exception) {
                log.warn("ReviewRisk model unavailable; switching fallback: productId={}, model={}",
                        input.productId(), model);
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return fallback(FallbackReason.AI_UNAVAILABLE, model);
            } catch (ResourceAccessException exception) {
                log.warn(
                        "ReviewRisk timed out; switching fallback: productId={}, model={}",
                        input.productId(), model);
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return fallback(FallbackReason.AI_UNAVAILABLE, model);
            } catch (RuntimeException exception) {
                log.warn(
                        "ReviewRisk AI request failed: productId={}, model={}, errorType={}",
                        input.productId(), model, exception.getClass().getSimpleName());
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return fallback(FallbackReason.AI_UNAVAILABLE, model);
            }
        }
    }

    private boolean hasNextModel(int modelIndex) {
        return modelIndex == 0 && models.size() > 1;
    }

    private boolean pauseBeforeRetry(long delayMillis, Long productId, String model) {
        try {
            retrySleeper.sleep(delayMillis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("ReviewRisk retry interrupted: productId={}, model={}", productId, model);
            return false;
        }
    }

    private static ReviewRiskResult fallback(FallbackReason reason, String model) {
        return new ReviewRiskResult(
                new ReviewRiskOutput(List.of(), zeroStatistics()),
                true,
                reason,
                false,
                model,
                ReviewRiskPromptFactory.PROMPT_VERSION);
    }

    private static List<ReviewTopicStatistic> zeroStatistics() {
        return Arrays.stream(ReviewRiskTopic.values())
                .map(topic -> new ReviewTopicStatistic(topic, BigDecimal.ZERO, Severity.LOW))
                .toList();
    }

    private static List<String> parseModels(String primaryModel, String fallbackModels) {
        LinkedHashSet<String> configured = new LinkedHashSet<>();
        addModel(configured, primaryModel);
        if (fallbackModels != null) {
            for (String model : fallbackModels.split(",")) addModel(configured, model);
        }
        if (configured.isEmpty()) throw new IllegalArgumentException("至少必須設定一個 Mistral ReviewRisk 模型");
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

    private record CacheKey(Long productId, long reviewCount, Long latestReviewId, String promptVersion) {}

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
