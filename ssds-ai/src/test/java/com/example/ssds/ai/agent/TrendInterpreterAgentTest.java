package com.example.ssds.ai.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.client.*;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.TrendInterpreterPromptFactory;
import com.example.ssds.ai.routing.AiAccessRouter;
import com.example.ssds.ai.schema.*;
import com.example.ssds.core.domain.HeatStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class TrendInterpreterAgentTest {
    @Test
    void successfulResultIsCachedForSameStageAndSlopeBucket() {
        FakeClient client = new FakeClient(TrendInterpreterResponseParserTest.validJson());
        TrendInterpreterAgent agent = agent(client);

        TrendInterpreterResult first = agent.interpret(TrendInterpreterResponseParserTest.input(), false);
        TrendInterpreterResult cached = agent.interpret(TrendInterpreterResponseParserTest.input(), false);

        assertFalse(first.cacheHit());
        assertTrue(cached.cacheHit());
        assertEquals(0, cached.requestCount());
        assertEquals(1, client.calls.get());
    }

    @Test
    void schemaFailureWaitsAndChangesModelOnce() {
        FakeClient client = new FakeClient(
                "{\"stage\":\"RISING\"}", TrendInterpreterResponseParserTest.validJson());

        TrendInterpreterResult result = agent(client).interpret(
                TrendInterpreterResponseParserTest.input(), false);

        assertFalse(result.fallbackApplied());
        assertEquals(2, result.requestCount());
        assertEquals(List.of("fake/primary", "fake/primary"), client.models);
    }

    @Test
    void unavailableServiceUsesRuleOutput() {
        FakeClient client = new FakeClient(new IllegalStateException("unavailable"));

        TrendInterpreterResult result = agent(client).interpret(
                TrendInterpreterResponseParserTest.input(), false);

        assertTrue(result.fallbackApplied());
        assertEquals(FallbackReason.AI_UNAVAILABLE, result.fallbackReason());
        assertEquals(HeatStage.RISING, result.output().stage());
        assertEquals(56, result.output().estimatedLifespanDays());
    }

    @Test
    void resourceAccessImmediatelySwitchesToFallback() {
        FakeClient client = new FakeClient(
                new ResourceAccessException("timeout"),
                TrendInterpreterResponseParserTest.validJson());

        TrendInterpreterResult result = agent(client).interpret(
                TrendInterpreterResponseParserTest.input(), false);

        assertFalse(result.fallbackApplied());
        assertEquals(2, result.requestCount());
        assertEquals(List.of("fake/primary", "fake/fallback"), client.models);
    }

    private static TrendInterpreterAgent agent(TrackAAiClient client) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new TrendInterpreterAgent(
                new AiAccessRouter(client),
                new TrendInterpreterPromptFactory(mapper),
                new TrendInterpreterResponseParser(mapper),
                mapper,
                "fake/primary",
                "fake/fallback,fake/third,fake/fourth",
                3,
                3,
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
            return new AiClientResponse((String) outcome, request.model(), 100, 20);
        }
    }
}
