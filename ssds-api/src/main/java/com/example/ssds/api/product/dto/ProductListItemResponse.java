package com.example.ssds.api.product.dto;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * FR-03 品項清單的單筆回應。
 */
public record ProductListItemResponse(
        Long id,
        String name,
        Long categoryId,
        String categoryName,
        Long supplierId,
        String supplierName,
        BigDecimal cost,
        BigDecimal suggestedPrice,
        BigDecimal marginRate,
        BigDecimal latestScore,
        Grade grade,
        TrackType trackType,
        SourcingStatus sourcingStatus,
        ProductStatus status,
        boolean hasRisk,
        OffsetDateTime updatedAt
) {
}