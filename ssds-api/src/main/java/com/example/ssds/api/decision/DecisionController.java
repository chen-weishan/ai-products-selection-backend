package com.example.ssds.api.decision;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.decision.DecisionQueryService.DecisionSearchCriteria;
import com.example.ssds.api.decision.dto.CampaignResultRequest;
import com.example.ssds.api.decision.dto.CloseDecisionRequest;
import com.example.ssds.api.decision.dto.CreateDecisionRequest;
import com.example.ssds.api.decision.dto.DecisionAccuracyResponse;
import com.example.ssds.api.decision.dto.DecisionContextResponse;
import com.example.ssds.api.decision.dto.DecisionResponse;
import com.example.ssds.api.decision.dto.DecisionSnapshotResponse;
import com.example.ssds.core.domain.DecisionType;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.time.LocalDate;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 採購決策與回饋閉環（規格書 §FR-11、§8.2「決策與回饋」）。
 *
 * <p>路徑不含 {@code /api/v1}，已由 context-path 統一加上。
 * 建立決策掛在品項底下（{@code /products/{id}/decisions}），其餘在 {@code /decisions}，
 * 與 §8.2 一致，因此類別層不設 {@code @RequestMapping}。
 */
@RestController
@RequiredArgsConstructor
public class DecisionController {

    private final DecisionCommandService commandService;
    private final DecisionQueryService queryService;
    private final DecisionAccuracyService accuracyService;

    // §2.1 權限列 11「記錄採購決策」：BUYER、BUYER_LEAD、SYS_ADMIN
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'SYS_ADMIN')")
    @PostMapping("/products/{productId}/decisions")
    public ResponseEntity<ApiResponse<DecisionResponse>> create(
            @PathVariable Long productId,
            @Valid @RequestBody CreateDecisionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        DecisionResponse dto = commandService.create(
                productId, request, actorOf(authentication), httpRequest.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/decisions/" + dto.id()))
                .body(ApiResponse.success(dto));
    }

    /** 規格補充：建立決策表單的判斷依據（綁定評分、AI 建議、§7.4 可選決策）。 */
    // §2.1 權限列 2：五個角色皆可讀
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/products/{productId}/decision-context")
    public ApiResponse<DecisionContextResponse> context(@PathVariable Long productId) {
        return ApiResponse.success(commandService.context(productId));
    }

    // §2.1 權限列 2：五個角色皆可讀
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/decisions")
    public ApiResponse<PageResponse<DecisionResponse>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long decidedBy,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) DecisionType decision,
            @RequestParam(required = false) Boolean pendingResult,
            @PageableDefault(size = 20, sort = "decidedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(queryService.list(
                new DecisionSearchCriteria(from, to, decidedBy, categoryId, productId, decision, pendingResult),
                pageable));
    }

    /** AC-11-4：可依時間區間、類別、決策者篩選。 */
    // §2.1 權限列 2：五個角色皆可讀
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/decisions/accuracy")
    public ApiResponse<DecisionAccuracyResponse> accuracy(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long decidedBy) {
        return ApiResponse.success(accuracyService.analyze(from, to, categoryId, decidedBy));
    }

    // §2.1 權限列 2：五個角色皆可讀
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/decisions/{id}")
    public ApiResponse<DecisionResponse> get(@PathVariable Long id) {
        return ApiResponse.success(queryService.get(id));
    }

    // §2.1 權限列 2：五個角色皆可讀
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/decisions/{id}/snapshot")
    public ApiResponse<DecisionSnapshotResponse> snapshot(@PathVariable Long id) {
        return ApiResponse.success(queryService.snapshot(id));
    }

    // §2.1 權限列 13「覆核決策」：BUYER_LEAD、SYS_ADMIN
    @PreAuthorize("hasAnyRole('BUYER_LEAD', 'SYS_ADMIN')")
    @PostMapping("/decisions/{id}/review")
    public ApiResponse<DecisionResponse> review(
            @PathVariable Long id, Authentication authentication, HttpServletRequest httpRequest) {
        return ApiResponse.success(commandService.review(id, actorOf(authentication), httpRequest.getRemoteAddr()));
    }

    // §2.1 權限列 12「標記結案與回填實際結果」：BUYER、BUYER_LEAD、DATA_ADMIN、SYS_ADMIN
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    @PostMapping("/decisions/{id}/close")
    public ApiResponse<DecisionResponse> close(
            @PathVariable Long id,
            @RequestBody(required = false) CloseDecisionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(
                commandService.close(id, request, actorOf(authentication), httpRequest.getRemoteAddr()));
    }

    // §2.1 權限列 12「標記結案與回填實際結果」：BUYER、BUYER_LEAD、DATA_ADMIN、SYS_ADMIN
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    @PostMapping("/decisions/{id}/result")
    public ApiResponse<DecisionResponse> fillResult(
            @PathVariable Long id,
            @Valid @RequestBody CampaignResultRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(
                commandService.fillResult(id, request, actorOf(authentication), httpRequest.getRemoteAddr()));
    }

    /** SecurityConfig 目前 permitAll，未帶 token 時取不到有效使用者（null 或匿名），一律交由 service 回 401。 */
    private static String actorOf(Authentication authentication) {
        return authentication == null ? null : authentication.getName();
    }
}
