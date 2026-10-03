package com.example.ssds.api.product.dto;

import java.util.List;

/** 停用關鍵字前供畫面確認的影響範圍。 */
public record TrendKeywordUsageResponse(
        Long keywordId,
        String keyword,
        boolean enabled,
        List<BoundProductResponse> products
) {
    public record BoundProductResponse(Long id, String name) {
    }
}
