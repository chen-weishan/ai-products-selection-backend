package com.example.ssds.api.aitask;

import com.example.ssds.ai.client.AiModelUnavailableEvent;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 模型 404 表示路由設定已過期；留下可由管理者追蹤的安全稽核紀錄。 */
@Component
public class AiModelUnavailableAuditListener {
    private static final Logger log = LoggerFactory.getLogger(AiModelUnavailableAuditListener.class);
    private final AuditLogRepository repository;
    private final ObjectMapper mapper;

    public AiModelUnavailableAuditListener(AuditLogRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @EventListener
    public void onUnavailable(AiModelUnavailableEvent event) {
        try {
            repository.save(AuditLog.builder()
                    .action("MODEL_CONFIGURATION_REQUIRED")
                    .entityType("AI_MODEL")
                    .afterJson(json(event))
                    .build());
        } catch (RuntimeException exception) {
            log.error("Unable to persist model configuration audit: modelAlias={}, model={}",
                    event.modelAlias(), event.model(), exception);
        }
    }

    private String json(AiModelUnavailableEvent event) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "modelAlias", event.modelAlias(),
                    "model", event.model(),
                    "httpStatus", event.httpStatus(),
                    "requiredAction", "UPDATE_MODEL_CONFIGURATION"));
        } catch (JsonProcessingException exception) {
            return "{\"requiredAction\":\"UPDATE_MODEL_CONFIGURATION\"}";
        }
    }
}
