package com.example.ssds.ai.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.client.*;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.SourcingScoutPromptFactory;
import com.example.ssds.ai.schema.SourcingScoutResponseParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

class SourcingScoutAgentTest {
    @Test
    void normalizedEquivalentKeywordsShareCacheEntry() {
        ObjectMapper mapper = new ObjectMapper();
        MistralSourcingClient client = mock(MistralSourcingClient.class);
        TrackBSourcingBudget budget = mock(TrackBSourcingBudget.class);
        when(client.complete(anyString(), anyString())).thenReturn(new ScoutClientResponse(
                """
                {"report":"搜尋資料顯示此關鍵字具有可持續觀察的市場訊號與風險。",
                 "opportunitySignals":["搜尋熱度可供觀察"],
                 "riskSignals":["來源資訊仍有限"]}
                """,
                "fake/primary", 10, 5, true, true));
        SourcingScoutAgent agent = new SourcingScoutAgent(
                client,
                new SourcingScoutPromptFactory(mapper),
                new SourcingScoutResponseParser(mapper),
                mapper,
                budget,
                "fake/primary",
                "",
                0,
                3,
                millis -> {});

        assertFalse(agent.scout(new SourcingScoutInput("熟凍帝王蟹", 10L, "水產"), false).cacheHit());
        assertTrue(agent.scout(new SourcingScoutInput(" 熟凍  帝王蟹", 10L, "水產"), false).cacheHit());
        verify(client, times(1)).complete(anyString(), anyString());
    }

    @Test
    void emptyModelConfigurationIsIsolatedUntilBTrackInvocation() {
        ObjectMapper mapper = new ObjectMapper();
        MistralSourcingClient client = mock(MistralSourcingClient.class);
        TrackBSourcingBudget budget = mock(TrackBSourcingBudget.class);
        SourcingScoutAgent agent = assertDoesNotThrow(() -> new SourcingScoutAgent(
                client,
                new SourcingScoutPromptFactory(mapper),
                new SourcingScoutResponseParser(mapper),
                mapper,
                budget,
                " ",
                " ",
                0,
                3,
                millis -> {}));

        assertThrows(
                SourcingConfigurationException.class,
                () -> agent.scout(new SourcingScoutInput("巧克力", 10L, "零食"), true));
        verifyNoInteractions(budget, client);
    }

    @Test
    void invalidSourcingCacheSettingIsIsolatedUntilBTrackInvocation() {
        ObjectMapper mapper = new ObjectMapper();
        MistralSourcingClient client = mock(MistralSourcingClient.class);
        TrackBSourcingBudget budget = mock(TrackBSourcingBudget.class);
        SourcingScoutAgent agent = assertDoesNotThrow(() -> new SourcingScoutAgent(
                client,
                new SourcingScoutPromptFactory(mapper),
                new SourcingScoutResponseParser(mapper),
                mapper,
                budget,
                "fake/primary",
                "",
                0,
                -1,
                millis -> {}));

        assertThrows(
                SourcingConfigurationException.class,
                () -> agent.scout(new SourcingScoutInput("巧克力", 10L, "零食"), true));
        verifyNoInteractions(budget, client);
    }

    @Test
    void nonNumericSourcingCacheSettingDoesNotFailConstruction() {
        ObjectMapper mapper = new ObjectMapper();
        MistralSourcingClient client = mock(MistralSourcingClient.class);
        TrackBSourcingBudget budget = mock(TrackBSourcingBudget.class);
        SourcingScoutAgent agent = assertDoesNotThrow(() -> new SourcingScoutAgent(
                client,
                new SourcingScoutPromptFactory(mapper),
                new SourcingScoutResponseParser(mapper),
                mapper,
                budget,
                mock(GlobalAiRateLimiter.class),
                "fake/primary",
                "",
                0,
                "not-a-number"));

        SourcingConfigurationException exception = assertThrows(
                SourcingConfigurationException.class,
                () -> agent.scout(new SourcingScoutInput("巧克力", 10L, "零食"), true));
        assertEquals("AI_CACHE_DAYS_SOURCING 必須是非負整數", exception.getMessage());
        verifyNoInteractions(budget, client);
    }

    @Test
    void globalLimitStopsBeforeBTrackBudgetAndHttpClient() {
        ObjectMapper mapper = new ObjectMapper();
        MistralSourcingClient client = mock(MistralSourcingClient.class);
        TrackBSourcingBudget budget = mock(TrackBSourcingBudget.class);
        GlobalAiRateLimiter rateLimiter = mock(GlobalAiRateLimiter.class);
        doThrow(new AiRateLimitException("limited", null)).when(rateLimiter).acquire();
        SourcingScoutAgent agent = new SourcingScoutAgent(
                client,
                new SourcingScoutPromptFactory(mapper),
                new SourcingScoutResponseParser(mapper),
                mapper,
                budget,
                rateLimiter,
                "fake/primary",
                "",
                0,
                3,
                millis -> {});

        assertThrows(
                IllegalStateException.class,
                () -> agent.scout(new SourcingScoutInput("巧克力", 10L, "零食"), true));

        verify(rateLimiter).acquire();
        verify(client).preflight();
        verifyNoInteractions(budget);
        verifyNoMoreInteractions(client);
    }

    @Test
    void clientConfigurationFailsBeforeRateLimitBudgetAndHttpAttempt() {
        ObjectMapper mapper = new ObjectMapper();
        MistralSourcingClient client = mock(MistralSourcingClient.class);
        TrackBSourcingBudget budget = mock(TrackBSourcingBudget.class);
        GlobalAiRateLimiter rateLimiter = mock(GlobalAiRateLimiter.class);
        doThrow(new SourcingConfigurationException("invalid setting"))
                .when(client).preflight();
        SourcingScoutAgent agent = new SourcingScoutAgent(
                client,
                new SourcingScoutPromptFactory(mapper),
                new SourcingScoutResponseParser(mapper),
                mapper,
                budget,
                rateLimiter,
                "fake/primary",
                "",
                0,
                3,
                millis -> {});

        assertThrows(
                SourcingConfigurationException.class,
                () -> agent.scout(new SourcingScoutInput("巧克力", 10L, "零食"), true));

        verify(client).preflight();
        verifyNoInteractions(rateLimiter, budget);
        verifyNoMoreInteractions(client);
    }

    @Test
    void connectorQuotaStopsWithoutRetryOrModelFallback() {
        ObjectMapper mapper = new ObjectMapper();
        MistralSourcingClient client = mock(MistralSourcingClient.class);
        TrackBSourcingBudget budget = mock(TrackBSourcingBudget.class);
        when(client.complete(anyString(), anyString()))
                .thenThrow(new SourcingConnectorQuotaExceededException(null));
        AtomicInteger sleeps = new AtomicInteger();
        SourcingScoutAgent agent = new SourcingScoutAgent(
                client,
                new SourcingScoutPromptFactory(mapper),
                new SourcingScoutResponseParser(mapper),
                mapper,
                budget,
                "fake/primary",
                "fake/fallback,fake/third",
                3,
                3,
                millis -> sleeps.incrementAndGet());

        SourcingConnectorQuotaExceededException thrown = assertThrows(
                SourcingConnectorQuotaExceededException.class,
                () -> agent.scout(new SourcingScoutInput("巧克力", 10L, "零食"), true));

        assertEquals("B 軌尋源 Connector 額度已達上限，請於服務額度重置後再試", thrown.getMessage());
        assertEquals(0, sleeps.get());
        verify(client, times(1)).complete(eq("fake/primary"), anyString());
        verify(budget).acquire(false);
    }

    @Test
    void resourceAccessImmediatelySwitchesToFallback() {
        ObjectMapper mapper = new ObjectMapper();
        MistralSourcingClient client = mock(MistralSourcingClient.class);
        TrackBSourcingBudget budget = mock(TrackBSourcingBudget.class);
        when(client.complete(anyString(), anyString()))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(new ScoutClientResponse(
                        """
                        {"report":"Google Trends 可驗證頁面顯示此關鍵字目前熱度大致持平，仍需持續觀察後續變化。",
                         "opportunitySignals":["熱度維持穩定"],
                         "riskSignals":["單一來源資訊有限"]}
                        """,
                "fake/third", 100, 30, true, true));
        SourcingScoutAgent agent = new SourcingScoutAgent(
                client,
                new SourcingScoutPromptFactory(mapper),
                new SourcingScoutResponseParser(mapper),
                mapper,
                budget,
                "fake/primary",
                "fake/fallback,fake/third",
                3,
                3,
                millis -> {});

        SourcingScoutResult result = agent.scout(
                new SourcingScoutInput("巧克力", 10L, "零食"), true);

        assertFalse(result.cacheHit());
        assertEquals(3, result.requestCount());
        ArgumentCaptor<String> models = ArgumentCaptor.forClass(String.class);
        verify(client, times(3)).complete(models.capture(), anyString());
        assertEquals(List.of("fake/primary", "fake/fallback", "fake/third"), models.getAllValues());
        var budgetOrder = inOrder(budget);
        budgetOrder.verify(budget).acquire(false);
        budgetOrder.verify(budget, times(2)).acquire(true);
    }
}
