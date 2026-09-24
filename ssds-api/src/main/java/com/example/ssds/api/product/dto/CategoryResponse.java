package com.example.ssds.api.product.dto;

/** 品類主檔寫入結果。 */
public record CategoryResponse(
        Long id,
        String name,
        Long parentId,
        String parentName,
        int sortOrder
) {}
