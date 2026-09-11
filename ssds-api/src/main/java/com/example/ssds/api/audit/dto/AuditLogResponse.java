package com.example.ssds.api.audit.dto;

import java.time.OffsetDateTime;

public record AuditLogResponse(
        Long id,
        Long userId,
        String userEmail,
        String userDisplayName,
        String action,
        String entityType,
        Long entityId,
        String beforeJson,
        String afterJson,
        String ip,
        OffsetDateTime createdAt
) {
}
