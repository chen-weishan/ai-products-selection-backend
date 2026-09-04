package com.example.ssds.api.product.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.product.dto.ProductReviewFileUploadResponse;
import com.example.ssds.api.product.dto.ProductReviewSummaryResponse;
import com.example.ssds.api.product.service.ProductReviewFileService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** S-04 單一品項的評論 CSV 補件 API。 */
@RestController
@RequestMapping("/products/{productId}/comments-file")
public class ProductReviewFileController {

    private final ProductReviewFileService reviewFileService;

    public ProductReviewFileController(ProductReviewFileService reviewFileService) {
        this.reviewFileService = reviewFileService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ProductReviewSummaryResponse> summary(
            @PathVariable(name = "productId") Long productId
    ) {
        return ApiResponse.success(reviewFileService.summary(productId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<ProductReviewFileUploadResponse> upload(
            @PathVariable(name = "productId") Long productId,
            @RequestPart(name = "file") MultipartFile file
    ) {
        return ApiResponse.success(reviewFileService.upload(productId, file));
    }
}
