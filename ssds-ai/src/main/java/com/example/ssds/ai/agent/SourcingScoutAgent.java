package com.example.ssds.ai.agent;

import com.example.ssds.ai.client.*;
import com.example.ssds.ai.config.MistralModelCatalog;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.SourcingScoutPromptFactory;
import com.example.ssds.ai.prompt.SourcingKeywordNormalizer;
import com.example.ssds.ai.schema.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.*;
import java.time.Duration;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

@Component
public class SourcingScoutAgent {
    private static final Logger log = LoggerFactory.getLogger(SourcingScoutAgent.class);
    private final MistralSourcingClient client;
    private final SourcingScoutPromptFactory promptFactory;
    private final SourcingScoutResponseParser parser;
    private final ObjectMapper mapper;
    private final List<String> models;
    private final int retryMax;
    private final TrackBSourcingBudget budget;
    private final GlobalAiRateLimiter rateLimiter;
    private final RetrySleeper retrySleeper;
    private final Cache<CacheKey, SourcingScoutResult> cache;
    private final String configurationError;

    @Autowired
    public SourcingScoutAgent(
            MistralSourcingClient client, SourcingScoutPromptFactory promptFactory,
            SourcingScoutResponseParser parser, ObjectMapper mapper,
            TrackBSourcingBudget budget, GlobalAiRateLimiter rateLimiter,
            MistralModelCatalog modelCatalog,
            @Value("${ai.retry-max:3}") int retryMax,
            @Value("${ai.cache-days-sourcing:3}") String cacheDays) {
        this(client, promptFactory, parser, mapper, budget, rateLimiter,
                modelCatalog.reasoning().primary(), modelCatalog.reasoning().fallbacks(),
                retryMax, cacheSetting(cacheDays), Thread::sleep);
    }

    SourcingScoutAgent(
            MistralSourcingClient client, SourcingScoutPromptFactory promptFactory,
            SourcingScoutResponseParser parser, ObjectMapper mapper,
            TrackBSourcingBudget budget, String primary, String fallbacks,
            int retryMax, long cacheDays, RetrySleeper retrySleeper) {
        this(client, promptFactory, parser, mapper, budget, GlobalAiRateLimiter.unrestrictedForTests(),
                primary, fallbacks, retryMax, cacheDays, retrySleeper);
    }

    SourcingScoutAgent(
            MistralSourcingClient client, SourcingScoutPromptFactory promptFactory,
            SourcingScoutResponseParser parser, ObjectMapper mapper,
            TrackBSourcingBudget budget, GlobalAiRateLimiter rateLimiter,
            String primary, String fallbacks,
            int retryMax, String cacheDays) {
        this(client, promptFactory, parser, mapper, budget, rateLimiter, primary, fallbacks,
                retryMax, cacheSetting(cacheDays), Thread::sleep);
    }

    SourcingScoutAgent(
            MistralSourcingClient client, SourcingScoutPromptFactory promptFactory,
            SourcingScoutResponseParser parser, ObjectMapper mapper,
            TrackBSourcingBudget budget, GlobalAiRateLimiter rateLimiter,
            String primary, String fallbacks,
            int retryMax, long cacheDays, RetrySleeper retrySleeper) {
        this(client, promptFactory, parser, mapper, budget, rateLimiter, primary, fallbacks,
                retryMax, cacheSetting(cacheDays), retrySleeper);
    }

    private SourcingScoutAgent(
            MistralSourcingClient client, SourcingScoutPromptFactory promptFactory,
            SourcingScoutResponseParser parser, ObjectMapper mapper,
            TrackBSourcingBudget budget, GlobalAiRateLimiter rateLimiter,
            String primary, String fallbacks,
            int retryMax, CacheSetting cacheSetting, RetrySleeper retrySleeper) {
        this.client = client; this.promptFactory = promptFactory; this.parser = parser; this.mapper = mapper; this.budget = budget;
        this.rateLimiter = rateLimiter;
        this.models = parseModels(primary, fallbacks); this.retryMax = Math.max(0, retryMax);
        this.retrySleeper = retrySleeper;
        this.configurationError = cacheSetting.error();
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofDays(cacheSetting.days()))
                .maximumSize(10_000).build();
    }

    public SourcingScoutResult scout(SourcingScoutInput input, boolean forceRefresh) {
        CacheKey key = new CacheKey(SourcingKeywordNormalizer.normalize(input.keyword()), input.categoryId(),
                SourcingScoutPromptFactory.PROMPT_VERSION);
        if (!forceRefresh) {
            SourcingScoutResult cached = cache.getIfPresent(key);
            if (cached != null) return cached.asCacheHit();
        }
        if (configurationError != null) throw new SourcingConfigurationException(configurationError);
        if (models.isEmpty()) {
            throw new SourcingConfigurationException("B 軌未設定任何 MODEL_REASONING 模型");
        }
        client.preflight();
        int modelIndex = 0, schemaRetries = 0, rateRetries = 0, requests = 0;
        String retryInstruction = null;
        while (true) {
            String model = models.get(modelIndex);
            try {
                log.info("SourcingScout request: modelAlias=MODEL_REASONING, model={}, promptVersion={}",
                        model, SourcingScoutPromptFactory.PROMPT_VERSION);
                String prompt = promptFactory.systemPrompt()
                        + (retryInstruction == null ? "" : "\n\n" + retryInstruction)
                        + "\n\nINPUT_JSON:\n" + promptFactory.userPrompt(input);
                rateLimiter.acquire();
                budget.acquire(requests > 0);
                requests++;
                ScoutClientResponse response = client.complete(model, prompt);
                SourcingScoutOutput output = parser.parse(response.content());
                SourcingScoutResult result = new SourcingScoutResult(output, false, response.model(),
                        SourcingScoutPromptFactory.PROMPT_VERSION, response.promptTokens(), response.completionTokens(), requests);
                cache.put(key, result);
                return result;
            } catch (AiSchemaValidationException | ScoutToolEvidenceException exception) {
                log.warn("SourcingScout response validation failed: model={}, errorType={}, reason={}",
                        model, exception.getClass().getSimpleName(), safe(exception.getMessage()));
                retryInstruction = promptFactory.retryInstruction(validationCode(exception));
                if (schemaRetries == 0) {
                    schemaRetries++;
                    if (pause(2_000L)) continue;
                }
                if (schemaRetries == 1 && hasFallbackModel(modelIndex)) {
                    schemaRetries++;
                    modelIndex++;
                    continue;
                }
                throw new IllegalStateException("尋源探索回應驗證失敗，功能暫時停用", exception);
            } catch (SourcingBudgetExceededException exception) {
                throw exception;
            } catch (AiBudgetExceededException exception) {
                throw exception;
            } catch (SourcingConfigurationException exception) {
                throw exception;
            } catch (SourcingConnectorQuotaExceededException exception) {
                log.warn("SourcingScout connector quota exhausted; stopping without retry or model fallback");
                throw exception;
            } catch (AiRateLimitException exception) {
                if (rateRetries < retryMax) {
                    long delay = 1_000L << rateRetries++;
                    if (pause(delay)) continue;
                }
                if (hasFallbackModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                throw new IllegalStateException("B 軌探索額度或速率限制已達上限，請於服務額度重置後再試", exception);
            } catch (AiModelNotFoundException exception) {
                log.warn("SourcingScout model unavailable; switching fallback: model={}", model);
                if (hasFallbackModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                throw new IllegalStateException("Mistral 尋源探索服務暫時無法使用", exception);
            } catch (ResourceAccessException exception) {
                log.warn("SourcingScout timed out; switching fallback: model={}", model);
                if (hasFallbackModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                throw new IllegalStateException("Mistral 尋源探索服務暫時無法使用", exception);
            } catch (RuntimeException exception) {
                if (hasFallbackModel(modelIndex)) {
                    modelIndex++;
                    continue;
                }
                throw new IllegalStateException("Mistral 尋源探索服務暫時無法使用", exception);
            }
        }
    }
    private boolean pause(long millis) {
        try { retrySleeper.sleep(millis); return true; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); return false; }
    }
    private static List<String> parseModels(String primary, String fallbacks) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (primary != null && !primary.isBlank()) values.add(primary.trim());
        if (fallbacks != null) Arrays.stream(fallbacks.split(",")).map(String::trim).filter(v -> !v.isBlank()).forEach(values::add);
        return List.copyOf(values);
    }
    private static CacheSetting cacheSetting(long days) {
        return days < 0
                ? new CacheSetting(0, "AI_CACHE_DAYS_SOURCING 不得小於 0")
                : new CacheSetting(days, null);
    }
    private static CacheSetting cacheSetting(String raw) {
        try {
            return cacheSetting(Long.parseLong(raw == null ? "" : raw.trim()));
        } catch (NumberFormatException exception) {
            return new CacheSetting(0, "AI_CACHE_DAYS_SOURCING 必須是非負整數");
        }
    }
    private boolean hasFallbackModel(int modelIndex) {
        return modelIndex + 1 < models.size();
    }
    private static String safe(String message) {
        if (message == null) return "unavailable";
        String value = message.replace('\r', ' ').replace('\n', ' ');
        return value.length() <= 160 ? value : value.substring(0, 160);
    }
    private static String validationCode(RuntimeException exception) {
        if (exception instanceof ScoutToolEvidenceException) return "WEB_EVIDENCE_MISSING";
        String message = exception.getMessage();
        return message != null && message.contains("report") ? "REPORT_INVALID" : "SCHEMA_INVALID";
    }
    private record CacheKey(String keyword, Long categoryId, String promptVersion) {}
    private record CacheSetting(long days, String error) {}

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
