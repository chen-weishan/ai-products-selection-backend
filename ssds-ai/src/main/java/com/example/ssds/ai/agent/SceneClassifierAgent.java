package com.example.ssds.ai.agent;

import com.example.ssds.ai.client.AiClientResponse;
import com.example.ssds.ai.client.AiPromptRequest;
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
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SceneClassifierAgent {
    private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.5");
    private final AiAccessRouter router;
    private final SceneClassifierPromptFactory promptFactory;
    private final SceneClassifierResponseParser parser;
    private final ObjectMapper objectMapper;
    private final String model;
    private final Cache<CacheKey, SceneClassificationResult> cache;

    public SceneClassifierAgent(
            AiAccessRouter router,
            SceneClassifierPromptFactory promptFactory,
            SceneClassifierResponseParser parser,
            ObjectMapper objectMapper,
            @Value("${openrouter.model-classify-primary:openrouter/free}") String model,
            @Value("${ai.cache-days:7}") long cacheDays) {
        this.router = router;
        this.promptFactory = promptFactory;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.model = model;
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofDays(cacheDays)).maximumSize(10_000).build();
    }

    public SceneClassificationResult classify(SceneClassifierInput input, boolean forceRefresh) {
        CacheKey key = new CacheKey(input.productId(), input.heatBucket());
        if (!forceRefresh) {
            SceneClassificationResult cached = cache.getIfPresent(key);
            if (cached != null) return cached.asCacheHit();
        }

        SceneClassificationResult result;
        String raw = null;
        try {
            AiPromptRequest request = new AiPromptRequest(
                    AiTaskType.SCENE_CLASSIFY,
                    model,
                    promptFactory.systemPrompt(),
                    promptFactory.userPrompt(input),
                    SceneClassifierSchema.create(objectMapper));
            AiClientResponse response = router.route(request);
            raw = response.content();
            SceneClassifierOutput output = parser.parse(raw);
            result = output.confidence().compareTo(MIN_CONFIDENCE) < 0
                    ? fallback(FallbackReason.LOW_CONFIDENCE, output.confidence(), output.reasoning(), output.signals(), raw, response.model())
                    : new SceneClassificationResult(output, false, null, false, raw, response.model(), SceneClassifierPromptFactory.PROMPT_VERSION);
        } catch (AiSchemaValidationException exception) {
            result = fallback(FallbackReason.SCHEMA_INVALID, null, "AI 回應格式驗證失敗", List.of("fallback: schema_invalid"), raw, model);
        } catch (RuntimeException exception) {
            result = fallback(FallbackReason.AI_UNAVAILABLE, null, "AI 服務暫時無法使用", List.of("fallback: ai_unavailable"), raw, model);
        }

        if (result.fallbackReason() == null || result.fallbackReason() == FallbackReason.LOW_CONFIDENCE) {
            cache.put(key, result);
        }
        return result;
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

    private record CacheKey(Long productId, HeatBucket heatBucket) {}
}
