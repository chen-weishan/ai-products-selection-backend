package com.example.ssds.api.product.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.product.dto.CategoryTreeResponse;
import com.example.ssds.api.product.dto.CategoryUpsertRequest;
import com.example.ssds.api.product.dto.CategoryResponse;
import com.example.ssds.api.product.dto.CategoryMarginMedianResponse;
import com.example.ssds.api.product.dto.FestivalOptionResponse;
import com.example.ssds.api.product.dto.SupplierResponse;
import com.example.ssds.api.product.dto.SupplierUpsertRequest;
import com.example.ssds.api.product.dto.TrendKeywordResponse;
import com.example.ssds.api.product.service.ProductReferenceQueryService;
import com.example.ssds.api.product.service.ReferenceDataCommandService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

/** 品項表單的品類、供應商與趨勢關鍵字查詢 API。 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class ProductReferenceController {

    private final ProductReferenceQueryService queryService;
    private final ReferenceDataCommandService commandService;

    public ProductReferenceController(
            ProductReferenceQueryService queryService,
            ReferenceDataCommandService commandService
    ) {
        this.queryService = queryService;
        this.commandService = commandService;
    }

    @GetMapping("/categories")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<CategoryTreeResponse>> getCategories() {
        return ApiResponse.success(queryService.getCategoryTree());
    }

    /** FR-13 品類樹維護；系統設定僅 SYS_ADMIN 可操作。 */
    @PostMapping("/categories")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public ApiResponse<CategoryResponse> createCategory(
            @Valid @RequestBody CategoryUpsertRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(commandService.createCategory(
                request,
                authentication.getName(),
                httpRequest.getRemoteAddr()
        ));
    }

    /** 規格未明列路徑，依既有資源式 API 風格採 PUT /categories/{id}。 */
    @PutMapping("/categories/{id}")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public ApiResponse<CategoryResponse> updateCategory(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody CategoryUpsertRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(commandService.updateCategory(
                id,
                request,
                authentication.getName(),
                httpRequest.getRemoteAddr()
        ));
    }

    @DeleteMapping("/categories/{id}")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public ApiResponse<Void> deleteCategory(
            @PathVariable(name = "id") Long id,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        commandService.deleteCategory(id, authentication.getName(), httpRequest.getRemoteAddr());
        return ApiResponse.success(null);
    }

    @GetMapping("/suppliers")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<SupplierResponse>> getSuppliers(
            @RequestParam(name = "keyword", required = false) String keyword
    ) {
        return ApiResponse.success(queryService.getSuppliers(keyword));
    }

    /** 規格 §8「POST /suppliers 維護」，沿用品項編輯權限。 */
    @PostMapping("/suppliers")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<SupplierResponse> createSupplier(
            @Valid @RequestBody SupplierUpsertRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(commandService.createSupplier(
                request,
                authentication.getName(),
                httpRequest.getRemoteAddr()
        ));
    }

    /** 規格未明列 id 位置，依既有資源式 API 風格採 PUT /suppliers/{id}。 */
    @PutMapping("/suppliers/{id}")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<SupplierResponse> updateSupplier(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody SupplierUpsertRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(commandService.updateSupplier(
                id,
                request,
                authentication.getName(),
                httpRequest.getRemoteAddr()
        ));
    }

    @DeleteMapping("/suppliers/{id}")
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public ApiResponse<Void> deleteSupplier(
            @PathVariable(name = "id") Long id,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        commandService.deleteSupplier(id, authentication.getName(), httpRequest.getRemoteAddr());
        return ApiResponse.success(null);
    }

    @GetMapping("/trends/keywords")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<TrendKeywordResponse>> getTrendKeywords(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "enabled", required = false) Boolean enabled
    ) {
        return ApiResponse.success(
                queryService.getTrendKeywords(keyword, enabled)
        );
    }

    @GetMapping("/festivals")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<FestivalOptionResponse>> getFestivals() {
        return ApiResponse.success(queryService.getFestivals());
    }

    @GetMapping("/categories/{categoryId}/margin-median")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CategoryMarginMedianResponse> getCategoryMarginMedian(
            @org.springframework.web.bind.annotation.PathVariable Long categoryId
    ) {
        return ApiResponse.success(queryService.getCategoryMarginMedian(categoryId));
    }
}
