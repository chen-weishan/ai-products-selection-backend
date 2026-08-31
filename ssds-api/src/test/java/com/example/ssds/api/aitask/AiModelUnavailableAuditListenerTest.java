package com.example.ssds.api.aitask;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.client.AiModelUnavailableEvent;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiModelUnavailableAuditListenerTest {
    @Test
    void model404CreatesSafeConfigurationAudit() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        AiModelUnavailableAuditListener listener =
                new AiModelUnavailableAuditListener(repository, new ObjectMapper());

        listener.onUnavailable(new AiModelUnavailableEvent(
                "MODEL_REASONING", "removed-model", 404));

        ArgumentCaptor<AuditLog> captured = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captured.capture());
        assertEquals("MODEL_CONFIGURATION_REQUIRED", captured.getValue().getAction());
        assertEquals("AI_MODEL", captured.getValue().getEntityType());
        assertTrue(captured.getValue().getAfterJson().contains("UPDATE_MODEL_CONFIGURATION"));
        assertFalse(captured.getValue().getAfterJson().contains("apiKey"));
    }
}
