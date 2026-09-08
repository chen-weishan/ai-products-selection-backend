package com.example.ssds.ai.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SourcingToolPolicyTest {
    @Test
    void allowsConfiguredSearchConnectors() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        SourcingToolPolicy policy = new SourcingToolPolicy(
                "parallel_search,exa_search,tavily_search", events);
        assertAll(
                () -> assertDoesNotThrow(() -> policy.requireAllowed("parallel_search")),
                () -> assertDoesNotThrow(() -> policy.requireAllowed("exa_search")),
                () -> assertDoesNotThrow(() -> policy.requireAllowed("tavily_search")));
        verifyNoInteractions(events);
    }

    @Test
    void rejectsAndAuditsToolOutsideWhitelist() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        SourcingToolPolicy policy = new SourcingToolPolicy("parallel_search,exa_search,tavily_search", events);
        assertThrows(SecurityException.class, () -> policy.requireAllowed("catalog.lookup"));
        verify(events).publishEvent(new SourcingToolRejectedEvent(
                "catalog.lookup", "工具不在 MCP_ALLOWED_TOOLS 白名單"));
    }
}
