package com.example.ssds.api.product.dto;

/** 品項圖片的中繼資料；圖片本體由 contentPath 取得。 */
public record ProductImageResponse(
        Long id,
        Long productId,
        String contentPath,
        int sortOrder
) {
}
