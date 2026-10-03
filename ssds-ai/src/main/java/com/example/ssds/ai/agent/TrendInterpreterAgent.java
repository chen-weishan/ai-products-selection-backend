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
import com.example.ssds.ai.model.trend.*;
import com.example.ssds.ai.prompt.trend.TrendInterpreterPromptFactory;
import com.example.ssds.ai.access.tracka.AiAccessRouter;
import com.example.ssds.ai.schema.AiSchemaValidationException;
import com.example.ssds.ai.schema.trend.TrendInterpreterResponseParser;
import com.example.ssds.ai.schema.trend.TrendInterpreterSchema;
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
            MistralModelCatalog modelCatalog,
            @Value("${ai.retry-max:3}") int retryMax,
            @Value("${ai.cache-days-trend:3}") long cacheDays) {
        this(router, promptFactory, parser, objectMapper,
                modelCatalog.numeric().primary(), modelCatalog.numeric().fallbacks(),
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
        this.models = new MistralModelCatalog.ModelChain(primaryModel, fallbackModels).models();
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
        RetryExecutionState retry = new RetryExecutionState(models, retryMax);
        while (true) {
            String model = retry.model();
            try {
                log.info(
                        "TrendInterpreter request: keywordId={}, modelAlias=MODEL_NUMERIC, model={}, promptVersion={}",
                        input.keywordId(), model, TrendInterpreterPromptFactory.PROMPT_VERSION);
                AiPromptRequest request = new AiPromptRequest(
                        AiTaskType.TREND_INTERPRET,
                        model,
                        promptFactory.systemPrompt()
                                + (retry.retryInstruction() == null ? "" : "\n\n" + retry.retryInstruction()),
                        promptFactory.userPrompt(input),
                        TrendInterpreterSchema.create(objectMapper),
                        retry.isRetryAttempt());
                retry.recordRequest();
                AiClientResponse response = router.route(request);
                TrendInterpreterOutput output = parser.parse(response.content(), input);
                return new TrendInterpreterResult(
                        output, false, null, false, response.model(),
                        TrendInterpreterPromptFactory.PROMPT_VERSION,
                        response.promptTokens(), response.completionTokens(), retry.requestCount());
            } catch (AiSchemaValidationException exception) {
                log.warn(
                        "TrendInterpreter schema validation failed: keywordId={}, model={}, reason={}",
                        input.keywordId(), model, SafeLogMessage.sanitize(exception.getMessage()));
                retry.retryInstruction(promptFactory.retryInstruction(validationCode(exception)));
                if (retry.beginSchemaRetry()) {
                    if (pause(2_000L, input.keywordId(), model)) continue;
                }
                if (retry.moveToSchemaFallback()) continue;
                return fallback(ruleOutput, FallbackReason.SCHEMA_INVALID, model, retry.requestCount());
            } catch (AiRateLimitException exception) {
                OptionalLong delay = retry.nextRateLimitDelay();
                if (delay.isPresent()) {
                    long backoffMillis = delay.getAsLong();
                    log.warn(
                            "TrendInterpreter rate limited; retrying same model: keywordId={}, model={}, retry={}/{}, backoffMs={}",
                            input.keywordId(), model, retry.rateLimitRetryCount(), retry.rateLimitRetryMax(), backoffMillis);
                    if (pause(backoffMillis, input.keywordId(), model)) continue;
                }
                if (retry.moveToNextModel()) continue;
                return fallback(ruleOutput, FallbackReason.AI_UNAVAILABLE, model, retry.requestCount());
            } catch (AiModelNotFoundException exception) {
                log.warn("TrendInterpreter model unavailable; switching fallback: keywordId={}, model={}",
                        input.keywordId(), model);
                if (retry.moveToNextModel()) continue;
                return fallback(ruleOutput, FallbackReason.AI_UNAVAILABLE, model, retry.requestCount());
            } catch (ResourceAccessException exception) {
                log.warn(
                        "TrendInterpreter timed out; switching fallback: keywordId={}, model={}",
                        input.keywordId(), model);
                if (retry.moveToNextModel()) continue;
                return fallback(ruleOutput, FallbackReason.AI_UNAVAILABLE, model, retry.requestCount());
            } catch (ExternalLlmDisabledException | OutboundDataPolicyException exception) {
                log.warn("TrendInterpreter external request blocked by policy: keywordId={}, reason={}",
                        input.keywordId(), SafeLogMessage.sanitize(exception.getMessage()));
                return fallback(ruleOutput, FallbackReason.AI_UNAVAILABLE, "policy-blocked",
                        Math.max(0, retry.requestCount() - 1));
            } catch (AiBudgetExceededException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                log.warn(
                        "TrendInterpreter request failed: keywordId={}, model={}, errorType={}",
                        input.keywordId(), model, exception.getClass().getSimpleName());
                if (retry.moveToNextModel()) continue;
                return fallback(ruleOutput, FallbackReason.AI_UNAVAILABLE, model, retry.requestCount());
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

    private static String validationCode(AiSchemaValidationException exception) {
        String message = exception.getMessage();
        if (message == null) return "SCHEMA_INVALID";
        if (isShapeError(message)) return "SHAPE_INVALID";
        if (message.contains("allowedOutputs") || message.contains("完整匹配")) return "OUTPUT_NOT_ALLOWED";
        if (message.contains("stage") && message.contains("列舉")) return "STAGE_INVALID";
        if (message.contains("整數")) return "INTEGER_INVALID";
        return "SCHEMA_INVALID";
    }

    private static boolean isShapeError(String message) {
        return message.contains("根物件") || message.contains("根節點")
                || message.contains("不得包含欄位") || message.contains("缺少欄位")
                || message.contains("欄位必須");
    }

    private record CacheKey(
            Long keywordId, HeatStage stage, int slope30Bucket, String promptVersion) {}

}
