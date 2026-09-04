package com.example.ssds.infra.dao.projection;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * FR-03 品項清單的資料庫查詢結果。
 *
 * <p>這不是 JPA Entity，而是跨表查詢的唯讀 projection。
 */
public record ProductListRow(
        Long productId,
        String productName,
        Long categoryId,
        String categoryName,
        Long supplierId,
        String supplierName,
        BigDecimal cost,
        BigDecimal suggestedPrice,
        BigDecimal marginRate,
        BigDecimal latestScore,
        Grade grade,
        Integer timeGapDays,
        TrackType trackType,
        SourcingStatus sourcingStatus,
        ProductStatus status,
        LastScoringStatus lastScoringStatus,
        Instant lastScoringAttemptedAt,
        boolean hasRisk,
        Instant updatedAt
) {
}
