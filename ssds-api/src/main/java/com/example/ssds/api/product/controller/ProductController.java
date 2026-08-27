package com.example.ssds.api.product.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.product.dto.ProductBatchCategoryRequest;
import com.example.ssds.api.product.dto.ProductBatchCategoryResponse;
import com.example.ssds.api.product.dto.ProductBatchAnalyzeRequest;
import com.example.ssds.api.product.dto.ProductBatchAnalyzeResponse;
import com.example.ssds.api.product.dto.ProductBatchDisableRequest;
import com.example.ssds.api.product.dto.ProductBatchDisableResponse;
import com.example.ssds.api.product.dto.ProductCreateRequest;
import com.example.ssds.api.product.dto.ProductCreateResponse;
import com.example.ssds.api.product.dto.ProductListItemResponse;
import com.example.ssds.api.product.dto.ProductResponse;
import com.example.ssds.api.product.dto.ProductSearchRequest;
import com.example.ssds.api.product.dto.ProductStatusUpdateRequest;
import com.example.ssds.api.product.dto.ProductStatusUpdateResponse;
import com.example.ssds.api.product.dto.ProductUpdateRequest;
import com.example.ssds.api.product.dto.ProductUpdateResponse;
import com.example.ssds.api.product.service.ProductCommandService;
import com.example.ssds.api.product.service.ProductAnalysisQueueService;
import com.example.ssds.api.product.service.ProductQueryService;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
        value = "/products",
        produces = MediaType.APPLICATION_JSON_VALUE
)
public class ProductController {

    private final ProductQueryService productQueryService;
    private final ProductCommandService productCommandService;
    private final ProductAnalysisQueueService productAnalysisQueueService;

    public ProductController(
            ProductQueryService productQueryService,
            ProductCommandService productCommandService,
            ProductAnalysisQueueService productAnalysisQueueService
    ) {
        this.productQueryService = productQueryService;
        this.productCommandService = productCommandService;
        this.productAnalysisQueueService = productAnalysisQueueService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<
            PageResponse<ProductListItemResponse>
            > search(
                @RequestParam(name = "keyword", required = false)
                String keyword,
                @RequestParam(name = "categoryId", required = false)
                Long categoryId,
                @RequestParam(name = "supplierId", required = false)
                Long supplierId,
                @RequestParam(name = "trackType", required = false)
                TrackType trackType,
                @RequestParam(name = "sourcingStatus", required = false)
                SourcingStatus sourcingStatus,
                @RequestParam(name = "status", required = false)
                ProductStatus status,
                @RequestParam(name = "grade", required = false)
                Grade grade,
                @RequestParam(name = "minScore", required = false)
                BigDecimal minScore,
                @RequestParam(name = "maxScore", required = false)
                BigDecimal maxScore,
                @RequestParam(name = "hasRisk", required = false)
                Boolean hasRisk,
                @PageableDefault(
                    size = 20,
                    sort = "latestScore",
                    direction = Sort.Direction.DESC
                )
                Pageable pageable
            ) {
        ProductSearchRequest request =
                new ProductSearchRequest(
                        keyword,
                        categoryId,
                        supplierId,
                        trackType,
                        sourcingStatus,
                        status,
                        grade,
                        minScore,
                        maxScore,
                        hasRisk
                );
        return ApiResponse.success(
                productQueryService.search(request, pageable)
        );
    }

    /** FR-03-2 取得品項完整資料，供編輯畫面載入。 */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ProductResponse> getById(
            @PathVariable(name = "id") Long id
    ) {
        return ApiResponse.success(
                productQueryService.getById(id)
        );
    }

    /** FR-03-2 新增品項。 */
    @PostMapping
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<ProductCreateResponse> create(
            @Valid @RequestBody ProductCreateRequest request,
            Authentication authentication
    ) {
        return ApiResponse.success(
                productCommandService.create(request, authentication.getName())
        );
    }

    /** FR-03-2 修改品項基本資料；草稿送出時才轉為待評估。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<ProductUpdateResponse> update(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody ProductUpdateRequest request
    ) {
        return ApiResponse.success(
                productCommandService.update(id, request)
        );
    }

    /** FR-03 依狀態機變更品項狀態。 */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<ProductStatusUpdateResponse> changeStatus(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody ProductStatusUpdateRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toSet());
        return ApiResponse.success(productCommandService.changeStatus(
                id,
                request,
                authentication.getName(),
                authorities,
                httpRequest.getRemoteAddr()
        ));
    }

    /** FR-03-1 批次停用；整批驗證成功後才執行軟刪除。 */
    @PatchMapping("/batch/disable")
    @PreAuthorize("hasAnyRole('BUYER_LEAD', 'SYS_ADMIN')")
    public ApiResponse<ProductBatchDisableResponse> disableBatch(
            @Valid @RequestBody ProductBatchDisableRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(productCommandService.disableBatch(
                request,
                authentication.getName(),
                httpRequest.getRemoteAddr()
        ));
    }

    /** FR-03-1 批次建立完整 AI 分析任務。 */
    @PostMapping("/batch/analyze")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<ProductBatchAnalyzeResponse> analyzeBatch(
            @Valid @RequestBody ProductBatchAnalyzeRequest request,
            Authentication authentication
    ) {
        return ApiResponse.success(productAnalysisQueueService.enqueue(
                request,
                authentication.getName()
        ));
    }

    /** FR-03-2 軟刪除品項；僅採購主管與系統管理員可執行。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('BUYER_LEAD', 'SYS_ADMIN')")
    public ApiResponse<Void> delete(
            @PathVariable(name = "id") Long id,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        productCommandService.delete(
                id,
                authentication.getName(),
                httpRequest.getRemoteAddr()
        );
        return ApiResponse.success(null);
    }

    /** FR-03-1 批次指定類別；任一品項不存在時整批不更新。 */
    @PatchMapping("/batch/category")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<ProductBatchCategoryResponse> assignCategory(
            @Valid @RequestBody ProductBatchCategoryRequest request
    ) {
        return ApiResponse.success(
                productCommandService.assignCategory(request)
        );
    }
}
