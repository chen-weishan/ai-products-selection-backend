package com.example.ssds.api.product.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.product.dto.ProductImageOrderRequest;
import com.example.ssds.api.product.dto.ProductImageResponse;
import com.example.ssds.api.product.service.ProductImageContent;
import com.example.ssds.api.product.service.ProductImageService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** FR-03 品項圖片子資源 API。 */
@RestController
@RequestMapping("/products/{productId}/images")
public class ProductImageController {

    private final ProductImageService imageService;

    public ProductImageController(ProductImageService imageService) {
        this.imageService = imageService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ProductImageResponse>> getImages(
            @PathVariable(name = "productId") Long productId
    ) {
        return ApiResponse.success(imageService.getImages(productId));
    }

    @GetMapping("/{imageId}/content")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> getContent(
            @PathVariable(name = "productId") Long productId,
            @PathVariable(name = "imageId") Long imageId
    ) {
        ProductImageContent content = imageService.getContent(productId, imageId);
        return ResponseEntity.ok()
                .contentType(content.mediaType())
                .cacheControl(CacheControl.noCache())
                .body(content.bytes());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<ProductImageResponse> upload(
            @PathVariable(name = "productId") Long productId,
            @RequestPart(name = "file") MultipartFile file
    ) {
        return ApiResponse.success(imageService.upload(productId, file));
    }

    @PatchMapping("/order")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<List<ProductImageResponse>> reorder(
            @PathVariable(name = "productId") Long productId,
            @Valid @RequestBody ProductImageOrderRequest request
    ) {
        return ApiResponse.success(imageService.reorder(productId, request));
    }

    @DeleteMapping("/{imageId}")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<Void> delete(
            @PathVariable(name = "productId") Long productId,
            @PathVariable(name = "imageId") Long imageId
    ) {
        imageService.delete(productId, imageId);
        return ApiResponse.success(null);
    }
}
