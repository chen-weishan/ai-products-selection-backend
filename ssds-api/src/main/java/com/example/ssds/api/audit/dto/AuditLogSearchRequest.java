package com.example.ssds.api.audit.dto;

import java.time.OffsetDateTime;

public record AuditLogSearchRequest(
        OffsetDateTime from,
        OffsetDateTime to,
        Long userId,
        String action,
        String entityType
) {
}
