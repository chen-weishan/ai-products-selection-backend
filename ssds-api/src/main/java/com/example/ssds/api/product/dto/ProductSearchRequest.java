package com.example.ssds.api.product.dto;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import java.math.BigDecimal;

/**
 * GET /products 的查詢條件。分頁與排序由 Pageable 提供。
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
        Boolean hasRisk
) {
}