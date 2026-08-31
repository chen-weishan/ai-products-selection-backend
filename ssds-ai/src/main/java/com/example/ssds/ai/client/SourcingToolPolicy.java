package com.example.ssds.ai.client;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** B 軌工具呼叫的單一出口；送出請求前先套用白名單。 */
@Component
public class SourcingToolPolicy {
    private final Set<String> allowedTools;
    private final ApplicationEventPublisher events;

    public SourcingToolPolicy(
            @Value("${mcp.allowed-tools:web_search}") String configured,
            ApplicationEventPublisher events) {
        this.allowedTools = Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        this.events = events;
    }

    public void requireAllowed(String tool) {
        if (allowedTools.contains(tool)) return;
        String reason = "工具不在 MCP_ALLOWED_TOOLS 白名單";
        events.publishEvent(new SourcingToolRejectedEvent(tool, reason));
        throw new SecurityException(reason + ": " + tool);
    }
}
