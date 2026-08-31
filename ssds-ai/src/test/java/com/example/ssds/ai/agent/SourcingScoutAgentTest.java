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
    void connectorQuotaStopsWithoutRetryOrModelFallback() {
        ObjectMapper mapper = new ObjectMapper();
        MistralSourcingClient client = mock(MistralSourcingClient.class);
        when(client.complete(anyString(), anyString()))
                .thenThrow(new SourcingConnectorQuotaExceededException(null));
        AtomicInteger sleeps = new AtomicInteger();
        SourcingScoutAgent agent = new SourcingScoutAgent(
                client,
                new SourcingScoutPromptFactory(mapper),
                new SourcingScoutResponseParser(mapper),
                mapper,
                new TrackBSourcingBudget(1000, 0.2),
                "fake/primary",
                "fake/fallback",
                3,
                3,
                millis -> sleeps.incrementAndGet());

        SourcingConnectorQuotaExceededException thrown = assertThrows(
                SourcingConnectorQuotaExceededException.class,
                () -> agent.scout(new SourcingScoutInput("巧克力", 10L, "零食"), true));

        assertEquals("B 軌尋源 Connector 額度已達上限，請於服務額度重置後再試", thrown.getMessage());
        assertEquals(0, sleeps.get());
        verify(client, times(1)).complete(eq("fake/primary"), anyString());
    }

    @Test
    void resourceAccessImmediatelySwitchesToFallback() {
        ObjectMapper mapper = new ObjectMapper();
        MistralSourcingClient client = mock(MistralSourcingClient.class);
        when(client.complete(anyString(), anyString()))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(new ScoutClientResponse(
                        """
                        {"report":"Google Trends 可驗證頁面顯示此關鍵字目前熱度大致持平，仍需持續觀察後續變化。",
                         "opportunitySignals":["熱度維持穩定"],
                         "riskSignals":["單一來源資訊有限"],
                         "heatStage":"PLATEAU"}
                        """,
                        "fake/fallback", 100, 30, true, true));
        SourcingScoutAgent agent = new SourcingScoutAgent(
                client,
                new SourcingScoutPromptFactory(mapper),
                new SourcingScoutResponseParser(mapper),
                mapper,
                new TrackBSourcingBudget(1000, 0.2),
                "fake/primary",
                "fake/fallback",
                3,
                3,
                millis -> {});

        SourcingScoutResult result = agent.scout(
                new SourcingScoutInput("巧克力", 10L, "零食"), true);

        assertFalse(result.cacheHit());
        assertEquals(2, result.requestCount());
        ArgumentCaptor<String> models = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).complete(models.capture(), anyString());
        assertEquals(List.of("fake/primary", "fake/fallback"), models.getAllValues());
    }
}
