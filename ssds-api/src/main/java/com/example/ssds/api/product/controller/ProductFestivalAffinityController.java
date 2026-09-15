package com.example.ssds.api.product.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.product.dto.ProductFestivalAffinityResponse;
import com.example.ssds.api.product.dto.ProductFestivalAffinityUpdateRequest;
import com.example.ssds.api.product.service.ProductFestivalAffinityService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 品項表單的節慶關聯度子資源 API。 */
@RestController
@RequestMapping("/products/{productId}/festival-affinity")
public class ProductFestivalAffinityController {

    private final ProductFestivalAffinityService affinityService;

    public ProductFestivalAffinityController(ProductFestivalAffinityService affinityService) {
        this.affinityService = affinityService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ProductFestivalAffinityResponse>> get(
            @PathVariable(name = "productId") Long productId
    ) {
        return ApiResponse.success(affinityService.get(productId));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<List<ProductFestivalAffinityResponse>> replace(
            @PathVariable(name = "productId") Long productId,
            @Valid @RequestBody ProductFestivalAffinityUpdateRequest request
    ) {
        return ApiResponse.success(affinityService.replace(productId, request));
    }
}
