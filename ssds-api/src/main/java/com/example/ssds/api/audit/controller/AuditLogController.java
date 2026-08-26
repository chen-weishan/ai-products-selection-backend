package com.example.ssds.api.audit.controller;

import com.example.ssds.api.audit.dto.AuditLogResponse;
import com.example.ssds.api.audit.dto.AuditLogSearchRequest;
import com.example.ssds.api.audit.service.AuditLogQueryService;
import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.common.response.PageResponse;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audit-logs")
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    public AuditLogController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('BUYER_LEAD', 'SYS_ADMIN')")
    public ApiResponse<PageResponse<AuditLogResponse>> search(
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime to,
            @RequestParam(name = "userId", required = false) Long userId,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "entityType", required = false) String entityType,
            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            ) Pageable pageable
    ) {
        return ApiResponse.success(auditLogQueryService.search(
                new AuditLogSearchRequest(from, to, userId, action, entityType),
                pageable
        ));
    }
}
