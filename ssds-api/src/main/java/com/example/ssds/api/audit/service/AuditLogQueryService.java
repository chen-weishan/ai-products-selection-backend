package com.example.ssds.api.audit.service;

import com.example.ssds.api.audit.dto.AuditLogResponse;
import com.example.ssds.api.audit.dto.AuditLogSearchRequest;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.repository.AuditLogRepository;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 稽核紀錄的唯讀複合查詢。 */
@Service
@Transactional(readOnly = true)
public class AuditLogQueryService {

    private static final Set<Integer> ALLOWED_PAGE_SIZES = Set.of(20, 50, 100);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");

    private final AuditLogRepository auditLogRepository;

    public AuditLogQueryService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public PageResponse<AuditLogResponse> search(
            AuditLogSearchRequest request,
            Pageable pageable
    ) {
        validate(request, pageable);

        Specification<AuditLog> specification = Specification.unrestricted();
        if (request.from() != null) {
            specification = specification.and((root, query, builder) ->
                    builder.greaterThanOrEqualTo(
                            root.get("createdAt"), request.from().toInstant()));
        }
        if (request.to() != null) {
            specification = specification.and((root, query, builder) ->
                    builder.lessThanOrEqualTo(
                            root.get("createdAt"), request.to().toInstant()));
        }
        if (request.userId() != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("user").get("id"), request.userId()));
        }
        String action = normalize(request.action());
        if (action != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("action"), action));
        }
        String entityType = normalize(request.entityType());
        if (entityType != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("entityType"), entityType));
        }

        return PageResponse.from(auditLogRepository
                .findAll(specification, pageable)
                .map(this::toResponse));
    }

    private void validate(AuditLogSearchRequest request, Pageable pageable) {
        if (pageable.getPageNumber() < 0) {
            throw validation("page", "page 不可小於 0");
        }
        if (!ALLOWED_PAGE_SIZES.contains(pageable.getPageSize())) {
            throw validation("size", "size 只允許 20、50 或 100");
        }
        List<Sort.Order> sortOrders = pageable.getSort().stream().toList();
        if (sortOrders.size() != 1
                || !"createdAt".equals(sortOrders.getFirst().getProperty())) {
            throw validation("sort", "稽核紀錄只允許依 createdAt 排序");
        }
        if (request.from() != null && request.to() != null
                && request.from().isAfter(request.to())) {
            throw validation("from", "from 不可晚於 to");
        }
    }

    private AuditLogResponse toResponse(AuditLog auditLog) {
        AppUser user = auditLog.getUser();
        return new AuditLogResponse(
                auditLog.getId(),
                user == null ? null : user.getId(),
                user == null ? null : user.getEmail(),
                user == null ? null : user.getDisplayName(),
                auditLog.getAction(),
                auditLog.getEntityType(),
                auditLog.getEntityId(),
                auditLog.getBeforeJson(),
                auditLog.getAfterJson(),
                auditLog.getIp(),
                auditLog.getCreatedAt().atZone(BUSINESS_ZONE).toOffsetDateTime()
        );
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BusinessException validation(String field, String message) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "查詢參數驗證失敗",
                List.of(new FieldError(field, message))
        );
    }
}
