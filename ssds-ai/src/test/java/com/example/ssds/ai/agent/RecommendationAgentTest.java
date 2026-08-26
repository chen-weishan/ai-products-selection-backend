package com.example.ssds.ai.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.client.*;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.RecommendationPromptFactory;
import com.example.ssds.ai.routing.AiAccessRouter;
import com.example.ssds.ai.schema.RecommendationResponseParser;
import com.example.ssds.ai.schema.RecommendationResponseParserTest;
import com.example.ssds.core.domain.DecisionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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
        assertEquals(List.of("fake/primary", "fake/fallback"), client.models);
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
        assertEquals(2, result.requestCount());
    }

    private static RecommendationAgent agent(TrackAAiClient client) {
        ObjectMapper mapper = new ObjectMapper();
        return new RecommendationAgent(
                new AiAccessRouter(client),
                new RecommendationPromptFactory(mapper),
                new RecommendationResponseParser(mapper),
                mapper,
                "fake/primary",
                "fake/fallback,fake/third,fake/fourth",
                3,
                6,
                millis -> {});
    }

    private static final class FakeClient implements TrackAAiClient {
        private final List<Object> outcomes;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> models = new ArrayList<>();

        private FakeClient(Object... outcomes) {
            this.outcomes = List.of(outcomes);
        }

        @Override
        public AiClientResponse complete(AiPromptRequest request) {
            int index = calls.getAndIncrement();
            models.add(request.model());
            Object outcome = outcomes.get(Math.min(index, outcomes.size() - 1));
            if (outcome instanceof RuntimeException exception) throw exception;
            return new AiClientResponse((String) outcome, request.model(), 100, 30);
        }
    }
}
