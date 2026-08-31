package com.example.ssds.api.sourcing;

import com.example.ssds.ai.client.SourcingToolRejectedEvent;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 白名單拒絕發生在外部呼叫之前，且不記錄 Prompt、金鑰或回應內容。 */
@Component
public class SourcingToolAuditListener {
    private final AuditLogRepository repository;
    private final ObjectMapper mapper;
    public SourcingToolAuditListener(AuditLogRepository repository, ObjectMapper mapper) {
        this.repository = repository; this.mapper = mapper;
    }
    @EventListener
    public void onRejected(SourcingToolRejectedEvent event) {
        repository.save(AuditLog.builder().action("MCP_TOOL_REJECTED")
                .entityType("SOURCING_TOOL").afterJson(json(event)).build());
    }
    private String json(SourcingToolRejectedEvent event) {
        try { return mapper.writeValueAsString(Map.of("tool", event.tool(), "reason", event.reason())); }
        catch (JsonProcessingException exception) { return "{\"reason\":\"serialization_failed\"}"; }
    }
}
