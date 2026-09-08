package com.example.ssds.ai.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.client.*;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.RecommendationPromptFactory;
import com.example.ssds.ai.routing.AiAccessRouter;
import com.example.ssds.ai.schema.RecommendationResponseParser;
import com.example.ssds.ai.schema.RecommendationResponseParserTest;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.Grade;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class RecommendationAgentTest {
    @Test
    void successfulResponseUsesCacheAndCountsOnlyRealRequests() {
        FakeClient client = new FakeClient(RecommendationResponseParserTest.validJson());
        RecommendationAgent agent = agent(client);

        RecommendationResult first = agent.recommend(RecommendationResponseParserTest.input(), false);
        RecommendationResult cached = agent.recommend(RecommendationResponseParserTest.input(), false);

        assertFalse(first.cacheHit());
        assertEquals(1, first.requestCount());
        assertTrue(cached.cacheHit());
        assertEquals(0, cached.requestCount());
        assertEquals(1, client.calls.get());
    }

    @Test
    void schemaFailureChangesModelAndThenSucceeds() {
        FakeClient client = new FakeClient("{\"invalid\":true}", RecommendationResponseParserTest.validJson());

        RecommendationResult result = agent(client).recommend(
                RecommendationResponseParserTest.input(), false);

        assertFalse(result.fallbackApplied());
        assertEquals(2, result.requestCount());
        assertEquals(List.of("fake/primary", "fake/primary"), client.models);
        assertEquals(List.of(false, true), client.retryAttempts);
    }

    @Test
    void unavailableModelsReturnRuleBasedFallbackWithoutBlockingScore() {
        FakeClient client = new FakeClient(new IllegalStateException("unavailable"));

        RecommendationResult result = agent(client).recommend(
                RecommendationResponseParserTest.input(), false);

        assertTrue(result.fallbackApplied());
        assertEquals(DecisionType.WATCH, result.output().action());
        assertEquals(0, result.output().qtyMin());
        assertEquals("暫不建議進貨", result.output().quantityText());
        assertEquals(3, result.requestCount());
    }

    @Test
    void fallbackActionDependsOnGradeAndPenaltyNotQuantityAvailability() {
        RecommendationInput original = RecommendationResponseParserTest.input();
        RecommendationInput gradeAWithoutQuantity = new RecommendationInput(
                original.productId(),
                original.factors(),
                original.bonusSubtotal(),
                original.penaltySubtotal(),
                Grade.A,
                original.sceneType(),
                original.matchedPenaltyRules(),
                original.festival(),
                List.of(0));

        RecommendationResult result = agent(new FakeClient(new IllegalStateException("unavailable")))
                .recommend(gradeAWithoutQuantity, false);

        assertEquals(DecisionType.ADOPT, result.output().action());
        assertEquals(0, result.output().qtyMin());
        assertEquals("建議採納，首批數量需人工確認", result.output().quantityText());
        assertEquals(
                "規則式預設建議：分級達採納條件且扣分未達風險抑制門檻，建議採納。",
                result.output().reasoning());
    }

    @Test
    void resourceAccessImmediatelySwitchesToFallback() {
        FakeClient client = new FakeClient(
                new ResourceAccessException("timeout"),
                RecommendationResponseParserTest.validJson());

        RecommendationResult result = agent(client).recommend(
                RecommendationResponseParserTest.input(), false);

        assertFalse(result.fallbackApplied());
        assertEquals(2, result.requestCount());
        assertEquals(List.of("fake/primary", "fake/fallback"), client.models);
    }

    private static RecommendationAgent agent(TrackAAiClient client) {
        ObjectMapper mapper = new ObjectMapper();
        return new RecommendationAgent(
                new AiAccessRouter(client),
                new RecommendationPromptFactory(mapper),
                new RecommendationResponseParser(mapper),
                mapper,
                "fake/primary",
                "fake/fallback,fake/third",
                3,
                6,
                millis -> {});
    }

    private static final class FakeClient implements TrackAAiClient {
        private final List<Object> outcomes;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> models = new ArrayList<>();
        private final List<Boolean> retryAttempts = new ArrayList<>();

        private FakeClient(Object... outcomes) {
            this.outcomes = List.of(outcomes);
        }

        @Override
        public AiClientResponse complete(AiPromptRequest request) {
            int index = calls.getAndIncrement();
            models.add(request.model());
            retryAttempts.add(request.retryAttempt());
            Object outcome = outcomes.get(Math.min(index, outcomes.size() - 1));
            if (outcome instanceof RuntimeException exception) throw exception;
            return new AiClientResponse((String) outcome, request.model(), 100, 30);
        }
    }
}
