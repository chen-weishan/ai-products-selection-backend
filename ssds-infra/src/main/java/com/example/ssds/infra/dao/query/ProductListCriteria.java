package com.example.ssds.infra.dao.query;

import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import java.math.BigDecimal;

/**
 * FR-03 品項清單查詢條件。
 *
 * <p>這是資料查詢條件，不是 HTTP Request DTO，
 * 因此放在 infra，不能依賴 ssds-api。
 */
public record ProductListCriteria(
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
        int page,
        int size,
        String sortField,
        boolean ascending
) {
}
