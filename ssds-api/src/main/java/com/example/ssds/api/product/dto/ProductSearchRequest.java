package com.example.ssds.api.product.dto;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import java.math.BigDecimal;

/**
 * GET /api/v1/products 的查詢條件。
 */
public record ProductSearchRequest(
        String keyword,
        Long categoryId,
        Long supplierId,
        TrackType trackType,
        SourcingStatus sourcingStatus,
        ProductStatus status,
        Grade grade,
        BigDecimal minScore,
        BigDecimal maxScore,
        Boolean hasRisk,
        Integer page,
        Integer size,
        String sort
) {

    public int resolvedPage() {
        return page == null ? 0 : page;
    }

    public int resolvedSize() {
        return size == null ? 20 : size;
    }

    public String resolvedSort() {
        return sort == null || sort.isBlank()
                ? "latestScore,desc"
                : sort.trim();
    }
}