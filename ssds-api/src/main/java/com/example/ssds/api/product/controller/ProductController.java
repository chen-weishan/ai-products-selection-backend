package com.example.ssds.api.product.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.product.dto.ProductCreateRequest;
import com.example.ssds.api.product.dto.ProductCreateResponse;
import com.example.ssds.api.product.dto.ProductListItemResponse;
import com.example.ssds.api.product.dto.ProductSearchRequest;
import com.example.ssds.api.product.dto.ProductUpdateRequest;
import com.example.ssds.api.product.dto.ProductUpdateResponse;
import com.example.ssds.api.product.service.ProductCommandService;
import com.example.ssds.api.product.service.ProductQueryService;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductQueryService productQueryService;
    private final ProductCommandService productCommandService;

    public ProductController(
            ProductQueryService productQueryService,
            ProductCommandService productCommandService
    ) {
        this.productQueryService = productQueryService;
        this.productCommandService = productCommandService;
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
                @RequestParam(name = "page", defaultValue = "0")
                Integer page,
                @RequestParam(name = "size", defaultValue = "20")
                Integer size,
                @RequestParam(
                    name = "sort",
                    defaultValue = "latestScore,desc"
                )
                String sort
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
                        hasRisk,
                        page,
                        size,
                        sort
                );
        return ApiResponse.success(
                productQueryService.search(request)
        );
    }

    /** FR-03-2 新增品項。 */
    @PostMapping
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'SYS_ADMIN')")
    public ApiResponse<ProductCreateResponse> create(
            @Valid @RequestBody ProductCreateRequest request
    ) {
        return ApiResponse.success(
                productCommandService.create(request)
        );
    }

    /** FR-03-2 修改品項基本資料，商品狀態由獨立 API 管理。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'SYS_ADMIN')")
    public ApiResponse<ProductUpdateResponse> update(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody ProductUpdateRequest request
    ) {
        return ApiResponse.success(
                productCommandService.update(id, request)
        );
    }
}
