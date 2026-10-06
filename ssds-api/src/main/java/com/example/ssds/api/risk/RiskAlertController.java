package com.example.ssds.api.risk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.dao.RiskRuleDao.RiskRuleRecord;

@RestController
@RequestMapping("/risks")
public class RiskAlertController {

    private final RiskAlertCommandService alerts;
    private final RiskAlertRuleCommandService rules;
    private final RiskRuleRecalculationService recalculation;
    private final RiskAlertSummaryService summary;

    public RiskAlertController(
            RiskAlertCommandService alerts,
            RiskAlertRuleCommandService rules,
            RiskRuleRecalculationService recalculation,
            RiskAlertSummaryService summary) {
        this.alerts = alerts;
        this.rules = rules;
        this.recalculation = recalculation;
        this.summary = summary;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResponse<RiskAlertResponse>> list(
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) Severity severity,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(required = false) Long categoryId,
            Pageable pageable) {
        return ApiResponse.success(alerts.search(status, severity, type, categoryId, pageable));
    }

    /** S-11 頂部 KPI 卡與「最後偵測」時間。 */
    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<RiskAlertSummaryService.Summary> summary() {
        return ApiResponse.success(summary.summary());
    }

    @PatchMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'SYS_ADMIN')")
    public ApiResponse<RiskAlertResponse> acknowledge(@PathVariable Long id) {
        return ApiResponse.success(alerts.acknowledge(id));
    }

    @PatchMapping("/{id}/ignore")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'SYS_ADMIN')")
    public ApiResponse<RiskAlertResponse> ignore(
            @PathVariable Long id, @Valid @RequestBody IgnoreRequest request) {
        return ApiResponse.success(alerts.ignore(id, request.reason()));
    }

    @GetMapping("/rules")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<RulesResponse> getRules() {
        return ApiResponse.success(new RulesResponse(rules.list(), recalculation.snapshot()));
    }

    @PutMapping("/rules/{code}")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public ApiResponse<RulesResponse> updateRule(
            @PathVariable String code, @Valid @RequestBody RiskAlertRuleCommandService.UpdateRequest request) {
        rules.update(code, request);
        return ApiResponse.success(new RulesResponse(rules.list(), recalculation.snapshot()));
    }

    public record IgnoreRequest(@NotBlank String reason) {}

    public record RulesResponse(
            java.util.List<RiskRuleRecord> rules,
            RiskRuleRecalculationService.Snapshot recalculation) {}
}
