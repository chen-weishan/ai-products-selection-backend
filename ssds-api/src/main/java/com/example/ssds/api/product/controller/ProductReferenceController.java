package com.example.ssds.api.product.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.product.dto.CategoryTreeResponse;
import com.example.ssds.api.product.dto.CategoryMarginMedianResponse;
import com.example.ssds.api.product.dto.SupplierResponse;
import com.example.ssds.api.product.dto.TrendKeywordResponse;
import com.example.ssds.api.product.service.ProductReferenceQueryService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 品項表單的品類、供應商與趨勢關鍵字查詢 API。 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class ProductReferenceController {

    private final ProductReferenceQueryService queryService;

    public ProductReferenceController(ProductReferenceQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/categories")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<CategoryTreeResponse>> getCategories() {
        return ApiResponse.success(queryService.getCategoryTree());
    }

    @GetMapping("/suppliers")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<SupplierResponse>> getSuppliers(
            @RequestParam(name = "keyword", required = false) String keyword
    ) {
        return ApiResponse.success(queryService.getSuppliers(keyword));
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

    // GET /festivals 已移至 FestivalController（FR-17）：規格書 §9 要求同一路徑支援
    // ?year= 回年度檔期，一個路徑不能有兩個 handler。不帶 year 的回應格式完全未變。

    @GetMapping("/categories/{categoryId}/margin-median")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CategoryMarginMedianResponse> getCategoryMarginMedian(
            @org.springframework.web.bind.annotation.PathVariable Long categoryId
    ) {
        return ApiResponse.success(queryService.getCategoryMarginMedian(categoryId));
    }
}
