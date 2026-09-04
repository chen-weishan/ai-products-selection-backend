package com.example.ssds.ai.routing;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.ssds.ai.client.AiClientResponse;
import com.example.ssds.ai.client.AiPromptRequest;
import com.example.ssds.ai.client.AiRateLimitException;
import com.example.ssds.ai.client.GlobalAiRateLimiter;
import com.example.ssds.ai.client.TrackAAiClient;
import com.example.ssds.core.domain.AiTaskType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AiAccessRouterTest {
    @Test
    void acquiresGlobalPermitBeforeCallingTrackAClient() {
        TrackAAiClient client = mock(TrackAAiClient.class);
        GlobalAiRateLimiter limiter = mock(GlobalAiRateLimiter.class);
        AiAccessRouter router = new AiAccessRouter(client, limiter);
        AiPromptRequest request = request(AiTaskType.SCENE_CLASSIFY);

        router.route(request);

        InOrder order = inOrder(limiter, client);
        order.verify(limiter).acquire();
        order.verify(client).complete(request);
    }

    @Test
    void doesNotCallTrackAClientWhenGlobalLimitIsReached() {
        TrackAAiClient client = mock(TrackAAiClient.class);
        GlobalAiRateLimiter limiter = mock(GlobalAiRateLimiter.class);
        org.mockito.Mockito.doThrow(new AiRateLimitException("limited", null))
                .when(limiter).acquire();
        AiAccessRouter router = new AiAccessRouter(client, limiter);

        assertThrows(AiRateLimitException.class, () -> router.route(request(AiTaskType.SCENE_CLASSIFY)));
        verifyNoInteractions(client);
    }

    private static AiPromptRequest request(AiTaskType type) {
        return new AiPromptRequest(
                type,
                "test-model",
                "system",
                "{}",
                JsonNodeFactory.instance.objectNode(),
                false);
    }
}
