package com.example.ssds.ai.agent;

import com.example.ssds.ai.client.*;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.TrendInterpreterPromptFactory;
import com.example.ssds.ai.routing.AiAccessRouter;
import com.example.ssds.ai.schema.*;
import com.example.ssds.core.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.*;
import java.math.*;
import java.time.Duration;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

@Component
public class TrendInterpreterAgent {
    private static final Logger log = LoggerFactory.getLogger(TrendInterpreterAgent.class);
    private final AiAccessRouter router;
    private final TrendInterpreterPromptFactory promptFactory;
    private final TrendInterpreterResponseParser parser;
    private final ObjectMapper objectMapper;
    private final List<String> models;
    private final int retryMax;
    private final RetrySleeper retrySleeper;
    private final Cache<CacheKey, TrendInterpreterResult> cache;

    @Autowired
    public TrendInterpreterAgent(
            AiAccessRouter router,
            TrendInterpreterPromptFactory promptFactory,
            TrendInterpreterResponseParser parser,
            ObjectMapper objectMapper,
            @Value("${mistral.model-numeric-primary:mistral-small-latest}") String primaryModel,
            @Value("${mistral.model-numeric-fallbacks:mistral-medium-3-5}") String fallbackModels,
            @Value("${ai.retry-max:3}") int retryMax,
            @Value("${ai.cache-days-trend:3}") long cacheDays) {
        this(router, promptFactory, parser, objectMapper, primaryModel, fallbackModels,
                retryMax, cacheDays, Thread::sleep);
    }

    TrendInterpreterAgent(
            AiAccessRouter router,
            TrendInterpreterPromptFactory promptFactory,
            TrendInterpreterResponseParser parser,
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

    public TrendInterpreterResult interpret(TrendInterpreterInput input, boolean forceRefresh) {
        TrendInterpreterOutput ruleOutput = TrendStageRules.evaluate(input);
        CacheKey key = new CacheKey(
                input.keywordId(),
                ruleOutput.stage(),
                slope30Bucket(input),
                TrendInterpreterPromptFactory.PROMPT_VERSION);
        if (!forceRefresh) {
            TrendInterpreterResult cached = cache.getIfPresent(key);
            if (cached != null && isAllowed(cached.output(), input)) return cached.asCacheHit();
            if (cached != null) cache.invalidate(key);
        }
        if (input.compositeSeries().isEmpty()) {
            return fallback(ruleOutput, FallbackReason.DATA_INSUFFICIENT, "not-invoked", 0);
        }
        TrendInterpreterResult result = interpretWithRetry(input, ruleOutput);
        if (!result.fallbackApplied()) cache.put(key, result);
        return result;
    }

    private TrendInterpreterResult interpretWithRetry(
            TrendInterpreterInput input, TrendInterpreterOutput ruleOutput) {
        int modelIndex = 0;
        int rateLimitRetries = 0;
        int schemaRetries = 0;
        int requestCount = 0;
        while (true) {
            String model = models.get(modelIndex);
            try {
                log.info(
                        "TrendInterpreter request: keywordId={}, modelAlias=MODEL_NUMERIC, model={}, promptVersion={}",
                        input.keywordId(), model, TrendInterpreterPromptFactory.PROMPT_VERSION);
                AiPromptRequest request = new AiPromptRequest(
                        AiTaskType.TREND_INTERPRET,
                        model,
                        promptFactory.systemPrompt(),
                        promptFactory.userPrompt(input),
                        TrendInterpreterSchema.create(objectMapper));
                requestCount++;
                AiClientResponse response = router.route(request);
                TrendInterpreterOutput output = parser.parse(response.content(), input);
                return new TrendInterpreterResult(
                        output, false, null, false, response.model(),
                        TrendInterpreterPromptFactory.PROMPT_VERSION,
                        response.promptTokens(), response.completionTokens(), requestCount);
            } catch (AiSchemaValidationException exception) {
                log.warn(
                        "TrendInterpreter schema validation failed: keywordId={}, model={}, reason={}",
                        input.keywordId(), model, safeLogMessage(exception.getMessage()));
                if (schemaRetries == 0) {
                    schemaRetries++;
                    if (pause(2_000L, input.keywordId(), model)) continue;
                }
                if (schemaRetries == 1 && hasNextModel(modelIndex)) {
                    schemaRetries++;
                    modelIndex++;
                    continue;
                }
                return fallback(ruleOutput, FallbackReason.SCHEMA_INVALID, model, requestCount);
            } catch (AiRateLimitException exception) {
                if (rateLimitRetries < retryMax) {
                    long backoffMillis = 1_000L << Math.min(rateLimitRetries, 10);
                    rateLimitRetries++;
                    log.warn(
                            "TrendInterpreter rate limited; retrying same model: keywordId={}, model={}, retry={}/{}, backoffMs={}",
                            input.keywordId(), model, rateLimitRetries, retryMax, backoffMillis);
                    if (pause(backoffMillis, input.keywordId(), model)) continue;
                }
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return fallback(ruleOutput, FallbackReason.AI_UNAVAILABLE, model, requestCount);
            } catch (AiModelNotFoundException exception) {
                log.warn("TrendInterpreter model unavailable; switching fallback: keywordId={}, model={}",
                        input.keywordId(), model);
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return fallback(ruleOutput, FallbackReason.AI_UNAVAILABLE, model, requestCount);
            } catch (ResourceAccessException exception) {
                log.warn(
                        "TrendInterpreter timed out; switching fallback: keywordId={}, model={}",
                        input.keywordId(), model);
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return fallback(ruleOutput, FallbackReason.AI_UNAVAILABLE, model, requestCount);
            } catch (RuntimeException exception) {
                log.warn(
                        "TrendInterpreter request failed: keywordId={}, model={}, errorType={}",
                        input.keywordId(), model, exception.getClass().getSimpleName());
                if (hasNextModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                return fallback(ruleOutput, FallbackReason.AI_UNAVAILABLE, model, requestCount);
            }
        }
    }

    private static TrendInterpreterResult fallback(
            TrendInterpreterOutput output, FallbackReason reason, String model, int requestCount) {
        return new TrendInterpreterResult(
                output, true, reason, false, model,
                TrendInterpreterPromptFactory.PROMPT_VERSION,
                null, null, requestCount);
    }

    private static int slope30Bucket(TrendInterpreterInput input) {
        BigDecimal slope = input.compositeSeries().isEmpty()
                ? null : input.compositeSeries().getLast().slope30d();
        if (slope == null) return Integer.MIN_VALUE;
        return slope.divide(new BigDecimal("0.10"), 0, RoundingMode.FLOOR).intValue();
    }

    private static boolean isAllowed(
            TrendInterpreterOutput output, TrendInterpreterInput input) {
        return input.allowedOutputs().stream().anyMatch(candidate ->
                candidate.stage() == output.stage()
                        && candidate.stageWeeks() == output.stageWeeks()
                        && candidate.estimatedLifespanDays() == output.estimatedLifespanDays());
    }

    private boolean hasNextModel(int index) {
        return index == 0 && models.size() > 1;
    }

    private boolean pause(long millis, Long keywordId, String model) {
        try {
            retrySleeper.sleep(millis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("TrendInterpreter retry interrupted: keywordId={}, model={}", keywordId, model);
            return false;
        }
    }

    private static List<String> parseModels(String primary, String fallbacks) {
        LinkedHashSet<String> configured = new LinkedHashSet<>();
        addModel(configured, primary);
        if (fallbacks != null) {
            for (String model : fallbacks.split(",")) addModel(configured, model);
        }
        if (configured.isEmpty()) throw new IllegalArgumentException("至少必須設定一個 TrendInterpreter 模型");
        return List.copyOf(configured);
    }

    private static void addModel(Set<String> configured, String model) {
        if (model != null && !model.isBlank()) configured.add(model.trim());
    }

    private static String safeLogMessage(String message) {
        if (message == null) return "unavailable";
        String sanitized = message.replace('\r', ' ').replace('\n', ' ');
        return sanitized.length() <= 160 ? sanitized : sanitized.substring(0, 160);
    }

    private record CacheKey(
            Long keywordId, HeatStage stage, int slope30Bucket, String promptVersion) {}

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
