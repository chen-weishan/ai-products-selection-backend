package com.example.ssds.api.product.dto;

import java.util.List;

/** 品項表單使用的兩層品類樹。 */
public record CategoryTreeResponse(
        Long id,
        String name,
        int sortOrder,
        List<CategoryTreeResponse> children
) {
}
