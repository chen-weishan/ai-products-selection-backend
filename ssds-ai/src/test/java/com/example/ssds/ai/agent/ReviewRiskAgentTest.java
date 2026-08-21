package com.example.ssds.ai.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.client.*;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.ReviewRiskPromptFactory;
import com.example.ssds.ai.routing.AiAccessRouter;
import com.example.ssds.ai.schema.ReviewRiskResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReviewRiskAgentTest {
    @Test
    void validResponseIsCachedUntilReviewVersionChanges() {
        FakeClient client = new FakeClient(validJson());
        ReviewRiskAgent agent = agent(client);

        assertFalse(agent.analyze(input(), 2, 2L, false).cacheHit());
        assertTrue(agent.analyze(input(), 2, 2L, false).cacheHit());
        assertFalse(agent.analyze(input(), 3, 3L, false).cacheHit());
        assertEquals(2, client.calls.get());
    }

    @Test
    void schemaFailureRetriesOnceWithNextModelThenFallsBack() {
        FakeClient client = new FakeClient("{\"unexpected\":true}");

        ReviewRiskResult result = agent(client).analyze(input(), 2, 2L, false);

        assertTrue(result.fallbackApplied());
        assertEquals(FallbackReason.SCHEMA_INVALID, result.fallbackReason());
        assertEquals(List.of("fake/primary", "fake/fallback"), client.models);
    }

    @Test
    void rateLimitRotatesModelsWithConfiguredBackoffCount() {
        FakeClient client = new FakeClient(
                new AiRateLimitException("rate limited", null), validJson());

        ReviewRiskResult result = agent(client).analyze(input(), 2, 2L, false);

        assertFalse(result.fallbackApplied());
        assertEquals(List.of("fake/primary", "fake/fallback"), client.models);
    }

    @Test
    void serviceFailureImmediatelySwitchesToFallbackModel() {
        FakeClient client = new FakeClient(new IllegalStateException("upstream unavailable"), validJson());

        ReviewRiskResult result = agent(client).analyze(input(), 2, 2L, false);

        assertFalse(result.fallbackApplied());
        assertEquals(List.of("fake/primary", "fake/fallback"), client.models);
    }

    private static ReviewRiskAgent agent(TrackAAiClient client) {
        ObjectMapper mapper = new ObjectMapper();
        return new ReviewRiskAgent(
                new AiAccessRouter(client),
                new ReviewRiskPromptFactory(mapper),
                new ReviewRiskResponseParser(mapper),
                mapper,
                "fake/primary",
                "fake/fallback,fake/third,fake/fourth",
                3,
                7,
                millis -> {});
    }

    private static ReviewRiskInput input() {
        return new ReviewRiskInput(
                101L,
                List.of(
                        new ReviewRiskInput.ReviewText(1L, "包裝破損"),
                        new ReviewRiskInput.ReviewText(2L, "味道不錯")));
    }

    private static String validJson() {
        return """
                {"reviews":[
                  {"reviewId":1,"sentiment":"NEGATIVE","riskTopic":"SHIPPING_DAMAGE"},
                  {"reviewId":2,"sentiment":"POSITIVE","riskTopic":null}],
                 "topicStatistics":[
                  {"topic":"QUALITY","ratio":0,"severity":"LOW"},
                  {"topic":"FOOD_SAFETY","ratio":0,"severity":"LOW"},
                  {"topic":"SHIPPING_DAMAGE","ratio":1,"severity":"HIGH"},
                  {"topic":"PRICE","ratio":0,"severity":"LOW"},
                  {"topic":"OTHER","ratio":0,"severity":"LOW"}]}
                """;
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
