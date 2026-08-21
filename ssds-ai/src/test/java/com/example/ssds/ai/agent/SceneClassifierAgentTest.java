package com.example.ssds.ai.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.client.*;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.SceneClassifierPromptFactory;
import com.example.ssds.ai.routing.AiAccessRouter;
import com.example.ssds.ai.schema.SceneClassifierResponseParser;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.Season;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SceneClassifierAgentTest {

    @Test
    void validMockLlmResponseReturnsEnumeratedScene() {
        CountingFakeClient fake = new CountingFakeClient("""
                {
                  "sceneType": "VIRAL_TOPIC",
                  "confidence": 0.82,
                  "reasoning": "近七日熱度快速上升且歷史開團次數少",
                  "alternativeScene": "SEASONAL",
                  "signals": ["heat_slope_7d: +3.40", "history_records: 2"]
                }
                """);

        SceneClassificationResult result = agent(fake).classify(input(101L, HeatBucket.VERY_HIGH), false);

        assertEquals(SceneCode.VIRAL_TOPIC, result.output().sceneType());
        assertEquals(new BigDecimal("0.82"), result.output().confidence());
        assertFalse(result.fallbackApplied());
        assertEquals(1, fake.calls.get());
    }

    @Test
    void confidenceBelowHalfFallsBackToReplenishmentAndIsMarked() {
        CountingFakeClient fake = new CountingFakeClient("""
                {
                  "sceneType": "FESTIVAL",
                  "confidence": 0.49,
                  "reasoning": "節慶關聯訊號不足",
                  "alternativeScene": "REPLENISHMENT",
                  "signals": ["festival_match: 0.20"]
                }
                """);

        SceneClassificationResult result = agent(fake).classify(input(101L, HeatBucket.MEDIUM), false);

        assertEquals(SceneCode.REPLENISHMENT, result.output().sceneType());
        assertTrue(result.fallbackApplied());
        assertEquals(FallbackReason.LOW_CONFIDENCE, result.fallbackReason());
    }

    @Test
    void schemaWithWeightFieldRetriesOnceFallsBackAndIsNotCached() {
        CountingFakeClient fake = new CountingFakeClient("""
                {
                  "sceneType": "VIRAL_TOPIC",
                  "confidence": 0.90,
                  "reasoning": "熱度上升",
                  "alternativeScene": null,
                  "signals": ["heat_slope_7d: +1.2"],
                  "weights": {"TREND": 0.9}
                }
                """);
        SceneClassifierAgent agent = agent(fake);

        SceneClassificationResult first = agent.classify(input(101L, HeatBucket.HIGH), false);
        SceneClassificationResult second = agent.classify(input(101L, HeatBucket.HIGH), false);

        assertEquals(SceneCode.REPLENISHMENT, first.output().sceneType());
        assertEquals(FallbackReason.SCHEMA_INVALID, first.fallbackReason());
        assertFalse(second.cacheHit());
        assertEquals(4, fake.calls.get());
    }

    @Test
    void schemaFailureRetriesOnceWithNextModel() {
        CountingFakeClient fake = new CountingFakeClient(
                """
                {"sceneType":"VIRAL_TOPIC","confidence":0.9,"reasoning":"熱度上升","alternativeScene":null,"signals":["heatSlope7d: 3.40"],"weights":{"TREND":0.9}}
                """,
                """
                {"sceneType":"VIRAL_TOPIC","confidence":0.82,"reasoning":"熱度上升","alternativeScene":"SEASONAL","signals":["heatSlope7d: 3.40"]}
                """);

        SceneClassificationResult result = agent(fake).classify(input(101L, HeatBucket.HIGH), false);

        assertFalse(result.fallbackApplied());
        assertEquals(List.of("fake/primary", "fake/fallback"), fake.models);
    }

    @Test
    void rateLimitRetriesWithNextModel() {
        CountingFakeClient fake = new CountingFakeClient(
                new AiRateLimitException("rate limited", null),
                """
                {"sceneType":"REPLENISHMENT","confidence":0.76,"reasoning":"需求穩定","alternativeScene":null,"signals":["historicalCampaignCount: 8"]}
                """);

        SceneClassificationResult result = agent(fake).classify(input(101L, HeatBucket.MEDIUM), false);

        assertFalse(result.fallbackApplied());
        assertEquals(List.of("fake/primary", "fake/fallback"), fake.models);
    }

    @Test
    void rateLimitStopsAfterThreeRetries() {
        CountingFakeClient fake = new CountingFakeClient(new AiRateLimitException("rate limited", null));

        SceneClassificationResult result = agent(fake).classify(input(101L, HeatBucket.MEDIUM), false);

        assertEquals(FallbackReason.AI_UNAVAILABLE, result.fallbackReason());
        assertEquals(
                List.of("fake/primary", "fake/fallback", "fake/third", "fake/fourth"),
                fake.models);
    }

    @Test
    void cacheKeyIncludesProductAndHeatBucket() {
        CountingFakeClient fake = new CountingFakeClient("""
                {
                  "sceneType": "REPLENISHMENT",
                  "confidence": 0.76,
                  "reasoning": "需求穩定",
                  "alternativeScene": null,
                  "signals": ["history_records: 8"]
                }
                """);
        SceneClassifierAgent agent = agent(fake);

        assertFalse(agent.classify(input(101L, HeatBucket.MEDIUM), false).cacheHit());
        assertTrue(agent.classify(input(101L, HeatBucket.MEDIUM), false).cacheHit());
        assertFalse(agent.classify(input(101L, HeatBucket.HIGH), false).cacheHit());
        assertFalse(agent.classify(input(102L, HeatBucket.MEDIUM), false).cacheHit());
        assertEquals(3, fake.calls.get());
    }

    @Test
    void promptContainsOnlyWhitelistedInputAndNoCommercialSecrets() {
        ObjectMapper mapper = new ObjectMapper();
        SceneClassifierPromptFactory factory = new SceneClassifierPromptFactory(mapper);

        String prompt = factory.userPrompt(input(101L, HeatBucket.HIGH));

        assertTrue(prompt.contains("productId"));
        assertTrue(prompt.contains("heatSlope7d"));
        assertFalse(prompt.contains("cost"));
        assertFalse(prompt.contains("suggestedPrice"));
        assertFalse(prompt.contains("margin"));
        assertFalse(prompt.contains("supplier"));
        assertFalse(prompt.contains("actualQty"));
    }

    @Test
    void trackBTaskCannotUseTrackAClient() {
        CountingFakeClient fake = new CountingFakeClient("{}");
        AiAccessRouter router = new AiAccessRouter(fake);
        AiPromptRequest request = new AiPromptRequest(
                AiTaskType.SOURCING_SCOUT, "model", "system", "user", new ObjectMapper().createObjectNode());

        assertThrows(IllegalArgumentException.class, () -> router.route(request));
        assertEquals(0, fake.calls.get());
    }

    private static SceneClassifierAgent agent(TrackAAiClient client) {
        ObjectMapper mapper = new ObjectMapper();
        return new SceneClassifierAgent(
                new AiAccessRouter(client),
                new SceneClassifierPromptFactory(mapper),
                new SceneClassifierResponseParser(mapper),
                mapper,
                "fake/primary",
                "fake/fallback,fake/third,fake/fourth",
                3,
                7,
                millis -> {});
    }

    private static SceneClassifierInput input(Long productId, HeatBucket bucket) {
        return new SceneClassifierInput(
                productId,
                "日式抹茶夾心餅乾",
                10L,
                "進口零食",
                Season.SUMMER,
                new BigDecimal("3.40"),
                new BigDecimal("1.25"),
                new BigDecimal("88.00"),
                bucket,
                2,
                List.of(new FestivalMatch("MID_AUTUMN", new BigDecimal("0.45"))));
    }

    private static final class CountingFakeClient implements TrackAAiClient {
        private final List<Object> outcomes;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> models = new ArrayList<>();

        private CountingFakeClient(Object... outcomes) {
            this.outcomes = List.of(outcomes);
        }

        @Override
        public AiClientResponse complete(AiPromptRequest request) {
            int call = calls.getAndIncrement();
            models.add(request.model());
            Object outcome = outcomes.get(Math.min(call, outcomes.size() - 1));
            if (outcome instanceof RuntimeException exception) throw exception;
            return new AiClientResponse((String) outcome, request.model(), 100, 30);
        }
    }
}
