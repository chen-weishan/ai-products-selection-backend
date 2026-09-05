package com.example.ssds.api.weight;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.weight.dto.ApproveWeightVersionRequest;
import com.example.ssds.api.weight.dto.CreateWeightVersionRequest;
import com.example.ssds.api.weight.dto.WeightVersionDetailResponse;
import com.example.ssds.api.weight.dto.WeightVersionSummaryResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.net.URI;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 權重版本與情境權重組（規格書 §FR-08、§8.2）。
 *
 * <p>
 * 路徑不含 {@code /api/v1} —— 已由 {@code server.servlet.context-path} 統一設定
 * （CONTEXT.md §5）。寫成 /api/v1/weight-versions 會變成
 * /api/v1/api/v1/weight-versions。
 */
@RestController
@RequestMapping("/weight-versions")
@RequiredArgsConstructor
public class WeightVersionController {

    private final WeightVersionQueryService queryService;
    private final WeightVersionCommandService commandService;

    /** 該版本的四組權重與四榜門檻。 */
    // TODO 權限列 2（§2.1）：全部已登入角色可讀 → @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}/profiles")
    public ApiResponse<WeightVersionDetailResponse> getProfiles(@PathVariable Long id) {
        return ApiResponse.success(queryService.getDetail(id));
    }

    /** 版本清單（摘要，不含權重明細）。沒有任何版本時回空頁，不是 404。 */
    // TODO 權限列 2（§2.1）：全部已登入角色可讀 → @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ApiResponse<PageResponse<WeightVersionSummaryResponse>> list(
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<WeightVersionSummaryResponse> page = queryService.list(pageable);
        return ApiResponse.success(PageResponse.from(page));
    }

    /** 目前生效中的版本（is_current = true），連同四組權重與四榜門檻。查無則 404。 */
    // TODO 權限列 2（§2.1）：全部已登入角色可讀 → @PreAuthorize("isAuthenticated()")
    @GetMapping("/active")
    public ApiResponse<WeightVersionDetailResponse> getActive() {
        return ApiResponse.success(queryService.getActive());
    }

    // TODO 權限列 15（§2.1）：僅 BUYER_LEAD → @PreAuthorize("hasRole('BUYER_LEAD')")
    @PostMapping
    public ResponseEntity<ApiResponse<WeightVersionDetailResponse>> create(
            @Valid @RequestBody CreateWeightVersionRequest request) {
        WeightVersionDetailResponse dto = commandService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/weight-versions/" + dto.id())).body(ApiResponse.success(dto));
    }

    // TODO 權限列 15（§2.1）：僅 BUYER_LEAD → @PreAuthorize("hasRole('BUYER_LEAD')")
    @PutMapping("/{id}")
    public ApiResponse<WeightVersionDetailResponse> update(
            @PathVariable Long id, @Valid @RequestBody CreateWeightVersionRequest request) {
        return ApiResponse.success(commandService.update(id, request));
    }

    // TODO 權限列 15（§2.1）、AC-08-3：僅 BUYER_LEAD → @PreAuthorize("hasRole('BUYER_LEAD')")
    @PostMapping("/{id}/approve")
    public ApiResponse<WeightVersionDetailResponse> approve(
            @PathVariable Long id, @Valid @RequestBody ApproveWeightVersionRequest request) {
        return ApiResponse.success(commandService.approve(id, request));
    }

}
