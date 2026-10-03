package com.example.ssds.ai.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.access.tracka.AiClientResponse;
import com.example.ssds.ai.access.tracka.AiPromptRequest;
import com.example.ssds.ai.access.tracka.TrackAAiClient;
import com.example.ssds.ai.budget.AiBudgetExceededException;
import com.example.ssds.ai.model.FallbackReason;
import com.example.ssds.ai.policy.ExternalLlmDisabledException;
import com.example.ssds.ai.resilience.AiRateLimitException;
import com.example.ssds.ai.model.trend.*;
import com.example.ssds.ai.prompt.trend.TrendInterpreterPromptFactory;
import com.example.ssds.ai.access.tracka.AiAccessRouter;
import com.example.ssds.ai.schema.trend.TrendInterpreterResponseParser;
import com.example.ssds.ai.schema.trend.TrendInterpreterResponseParserTest;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.HeatStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
        assertNull(cached.promptTokens());
        assertNull(cached.completionTokens());
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
        assertFalse(client.systemPrompts.get(0).contains("修正要求"));
        assertTrue(client.systemPrompts.get(1).contains("TrendInterpreter Schema"));
        assertTrue(client.systemPrompts.get(1).contains("SHAPE_INVALID"));
        assertEquals(List.of("fake/primary", "fake/primary"), client.models);
        assertEquals(List.of(false, true), client.retryAttempts);
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
    void unavailableServiceUsesCoreRuleForEveryThirtyDaySlopeBoundary() {
        record Case(String slope30d, HeatStage stage, int lifespanDays) {}
        List<Case> cases = List.of(
                new Case("-0.11", HeatStage.DECLINING, 17),
                new Case("-0.10", HeatStage.PLATEAU, 42),
                new Case("0", HeatStage.PLATEAU, 42),
                new Case("0.10", HeatStage.PLATEAU, 42),
                new Case("0.11", HeatStage.RISING, 56),
                new Case(null, HeatStage.PLATEAU, 42));

        for (Case value : cases) {
            TrendInterpreterResult result = agent(new FakeClient(
                    new IllegalStateException("unavailable")))
                    .interpret(inputAtSlope(value.slope30d()), false);
            assertAll(
                    () -> assertTrue(result.fallbackApplied()),
                    () -> assertEquals(value.stage(), result.output().stage()),
                    () -> assertEquals(value.lifespanDays(), result.output().estimatedLifespanDays()));
        }
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

    @Test
    void externalLlmDisabledFallsBackWithoutCountingExternalRequest() {
        FakeClient client = new FakeClient(new ExternalLlmDisabledException("disabled"));

        TrendInterpreterResult result = agent(client).interpret(
                TrendInterpreterResponseParserTest.input(), false);

        assertTrue(result.fallbackApplied());
        assertEquals(FallbackReason.AI_UNAVAILABLE, result.fallbackReason());
        assertEquals(0, result.requestCount());
    }

    @Test
    void rateLimitRetriesSameModelBeforeSucceeding() {
        FakeClient client = new FakeClient(
                new AiRateLimitException("rate limited", null),
                TrendInterpreterResponseParserTest.validJson());

        TrendInterpreterResult result = agent(client).interpret(
                TrendInterpreterResponseParserTest.input(), false);

        assertFalse(result.fallbackApplied());
        assertEquals(2, result.requestCount());
        assertEquals(List.of("fake/primary", "fake/primary"), client.models);
    }

    @Test
    void persistentSchemaFailureFallsBackToRuleAfterModelFallback() {
        FakeClient client = new FakeClient("{}", "{}", "{}");

        TrendInterpreterResult result = agent(client).interpret(
                TrendInterpreterResponseParserTest.input(), false);

        assertTrue(result.fallbackApplied());
        assertEquals(FallbackReason.SCHEMA_INVALID, result.fallbackReason());
        assertEquals(3, result.requestCount());
        assertEquals(
                List.of("fake/primary", "fake/primary", "fake/fallback"),
                client.models);
    }

    @Test
    void budgetExhaustionIsPropagatedToTaskWorker() {
        AiBudgetExceededException exhausted = new AiBudgetExceededException(
                AiTaskType.BudgetPool.TRACK_A,
                OffsetDateTime.parse("2026-09-23T00:00:00+08:00"));
        FakeClient client = new FakeClient(exhausted);

        assertSame(exhausted, assertThrows(
                AiBudgetExceededException.class,
                () -> agent(client).interpret(
                        TrendInterpreterResponseParserTest.input(), false)));
    }

    private static TrendInterpreterAgent agent(TrackAAiClient client) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new TrendInterpreterAgent(
                new AiAccessRouter(client),
                new TrendInterpreterPromptFactory(mapper),
                new TrendInterpreterResponseParser(mapper),
                mapper,
                "fake/primary",
                "fake/fallback,fake/third",
                3,
                3,
                millis -> {});
    }

    private static TrendInterpreterInput inputAtSlope(String slope30d) {
        return new TrendInterpreterInput(
                1L,
                List.of(new TrendInterpreterInput.CompositePoint(
                        "2026-09-22",
                        new BigDecimal("50"),
                        new BigDecimal("9.99"),
                        slope30d == null ? null : new BigDecimal(slope30d))),
                List.of(),
                List.of(
                        new TrendInterpreterInput.AllowedOutput(HeatStage.RISING, 1, 56),
                        new TrendInterpreterInput.AllowedOutput(HeatStage.PLATEAU, 1, 42),
                        new TrendInterpreterInput.AllowedOutput(HeatStage.DECLINING, 1, 17)));
    }

    private static final class FakeClient implements TrackAAiClient {
        private final List<Object> outcomes;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> models = new ArrayList<>();
        private final List<Boolean> retryAttempts = new ArrayList<>();
        private final List<String> systemPrompts = new ArrayList<>();

        private FakeClient(Object... outcomes) {
            this.outcomes = List.of(outcomes);
        }

        @Override
        public AiClientResponse complete(AiPromptRequest request) {
            int index = calls.getAndIncrement();
            models.add(request.model());
            retryAttempts.add(request.retryAttempt());
            systemPrompts.add(request.systemPrompt());
            Object outcome = outcomes.get(Math.min(index, outcomes.size() - 1));
            if (outcome instanceof RuntimeException exception) throw exception;
            return new AiClientResponse((String) outcome, request.model(), 100, 20);
        }
    }
}
