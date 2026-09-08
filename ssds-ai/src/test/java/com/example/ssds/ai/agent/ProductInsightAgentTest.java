package com.example.ssds.ai.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.client.*;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.ProductInsightPromptFactory;
import com.example.ssds.ai.routing.AiAccessRouter;
import com.example.ssds.ai.schema.ProductInsightResponseParser;
import com.example.ssds.core.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class ProductInsightAgentTest {
    @Test
    void successfulOutputIsCachedByReviewBucketDateAndPromptVersion() {
        FakeClient client = new FakeClient(validJson());
        ProductInsightAgent agent = agent(client);

        ProductInsightResult first = agent.analyze(input(), 0, LocalDate.of(2026, 8, 25), false);
        ProductInsightResult cached = agent.analyze(input(), 0, LocalDate.of(2026, 8, 25), false);
        ProductInsightResult nextDate = agent.analyze(input(), 0, LocalDate.of(2026, 8, 26), false);

        assertFalse(first.cacheHit());
        assertEquals(1, first.requestCount());
        assertTrue(cached.cacheHit());
        assertEquals(0, cached.requestCount());
        assertFalse(nextDate.cacheHit());
        assertEquals(2, client.calls.get());
    }

    @Test
    void schemaFailureWaitsThenChangesModelOnce() {
        FakeClient client = new FakeClient("{\"invalid\":true}", validJson());

        ProductInsightResult result = agent(client).analyze(
                input(), 0, LocalDate.of(2026, 8, 25), false);

        assertFalse(result.fallbackApplied());
        assertEquals(2, result.requestCount());
        assertEquals(List.of("fake/primary", "fake/primary"), client.models);
    }

    @Test
    void rateLimitUsesNextModelAndCountsBothRequests() {
        FakeClient client = new FakeClient(
                new AiRateLimitException("rate limited", null), validJson());

        ProductInsightResult result = agent(client).analyze(
                input(), 0, LocalDate.of(2026, 8, 25), false);

        assertFalse(result.fallbackApplied());
        assertEquals(2, result.requestCount());
        assertEquals(List.of("fake/primary", "fake/primary"), client.models);
    }

    @Test
    void resourceAccessImmediatelySwitchesToFallback() {
        FakeClient client = new FakeClient(
                new ResourceAccessException("timeout"),
                validJson());

        ProductInsightResult result = agent(client).analyze(
                input(), 0, LocalDate.of(2026, 8, 25), false);

        assertFalse(result.fallbackApplied());
        assertEquals(2, result.requestCount());
        assertEquals(List.of("fake/primary", "fake/fallback"), client.models);
    }

    private static ProductInsightAgent agent(TrackAAiClient client) {
        ObjectMapper mapper = new ObjectMapper();
        return new ProductInsightAgent(
                new AiAccessRouter(client),
                new ProductInsightPromptFactory(mapper),
                new ProductInsightResponseParser(mapper),
                mapper,
                "fake/primary",
                "fake/fallback,fake/third",
                3,
                6,
                millis -> {});
    }

    private static ProductInsightInput input() {
        return new ProductInsightInput(
                101L,
                new ProductInsightInput.ProductBasic("抹茶餅乾", "零食", Season.ALL, "常溫"),
                List.of(
                        new ProductInsightInput.ReviewText(1L, "茶味香濃"),
                        new ProductInsightInput.ReviewText(2L, "口感酥脆"),
                        new ProductInsightInput.ReviewText(3L, "品質偶有差異")),
                List.of(new ProductInsightInput.PenaltyDetail(
                        FactorCode.REVIEW_RISK, new BigDecimal("8.0"), List.of("QUALITY"))));
    }

    private static String validJson() {
        return """
                {"sellingPoints":[
                  {"text":"茶味受到肯定","supportCount":1,"aspect":"口味"},
                  {"text":"口感酥脆","supportCount":1,"aspect":"口感"}],
                 "risks":[
                  {"text":"品質偶有差異","type":"QUALITY","severity":"MEDIUM","countedInPenalty":true},
                  {"text":"價格資料有限","type":"PRICE","severity":"LOW","countedInPenalty":false}]}
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
            return new AiClientResponse((String) outcome, request.model(), 120, 40);
        }
    }
}
