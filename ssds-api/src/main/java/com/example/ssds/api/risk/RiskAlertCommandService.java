package com.example.ssds.api.risk;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.security.CurrentUserId;
import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.RiskAlert;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.RiskAlertRepository;

@Service
public class RiskAlertCommandService {

    private final RiskAlertRepository alerts;
    private final AppUserRepository users;
    private final AuditLogRepository audits;
    private final ObjectMapper objectMapper;
    private final RiskImpactService impacts;

    public RiskAlertCommandService(
            RiskAlertRepository alerts,
            AppUserRepository users,
            AuditLogRepository audits,
            ObjectMapper objectMapper,
            RiskImpactService impacts) {
        this.alerts = alerts;
        this.users = users;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.impacts = impacts;
    }

    @Transactional(readOnly = true)
    public PageResponse<RiskAlertResponse> search(
            AlertStatus status, Severity severity, String type, Long categoryId, String keyword, Pageable pageable) {
        Page<RiskAlert> page = status == null && severity == null && type == null && categoryId == null && (keyword == null || keyword.isBlank())
                ? alerts.findVisible(null, pageable)
                : alerts.search(status, severity, type, categoryId, keyword == null || keyword.isBlank() ? null : keyword.trim(), pageable);
        Map<Long, String> impactByAlert = impacts.describe(page.getContent());
        return PageResponse.from(page.map(alert ->
                RiskAlertResponse.from(alert, impactByAlert.get(alert.getId()))));
    }

    @Transactional
    public RiskAlertResponse acknowledge(Long id) {
        RiskAlert alert = find(id);
        ensureOpen(alert);
        Long userId = CurrentUserId.require();
        alert.setStatus(AlertStatus.ACKNOWLEDGED);
        alert.setHandledAt(Instant.now());
        alert.setHandledBy(users.getReferenceById(userId));
        alerts.save(alert);
        audit("ACKNOWLEDGE", alert, null);
        return respond(alert);
    }

    @Transactional
    public RiskAlertResponse ignore(Long id, String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 300) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "忽略理由必填，且不得超過 300 字");
        }
        RiskAlert alert = find(id);
        ensureOpen(alert);
        Long userId = CurrentUserId.require();
        alert.setStatus(AlertStatus.IGNORED);
        alert.setIgnoreReason(reason.trim());
        alert.setHandledAt(Instant.now());
        alert.setHandledBy(users.getReferenceById(userId));
        alerts.save(alert);
        audit("IGNORE", alert, reason.trim());
        return respond(alert);
    }

    private RiskAlert find(Long id) {
        return alerts.findForUpdate(id).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到風險示警 id=" + id));
    }

    private static void ensureOpen(RiskAlert alert) {
        if (alert.getStatus() != AlertStatus.OPEN) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "只有待處理示警可以再次處理");
        }
    }

    private void audit(String action, RiskAlert alert, String reason) {
        audits.save(AuditLog.builder()
                .user(users.getReferenceById(CurrentUserId.require()))
                .action(action)
                .entityType("RiskAlert")
                .entityId(alert.getId())
                .afterJson(afterJson(alert, reason))
                .build());
    }

    /** 稽核的 after 快照；有理由（忽略）時一併寫入，示警本身日後被改動也查得到當時填了什麼。 */
    private String afterJson(RiskAlert alert, String reason) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", alert.getStatus().name());
        if (reason != null) {
            after.put("reason", reason);
        }
        try {
            return objectMapper.writeValueAsString(after);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("無法建立示警處理稽核內容", exception);
        }
    }

    private RiskAlertResponse respond(RiskAlert alert) {
        return RiskAlertResponse.from(alert, impacts.describe(List.of(alert)).get(alert.getId()));
    }
}