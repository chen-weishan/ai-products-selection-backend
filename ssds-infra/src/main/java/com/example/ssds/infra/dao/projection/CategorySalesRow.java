package com.example.ssds.infra.dao.projection;

import java.math.BigDecimal;

/** 類別銷售彙總（FR-12 報表、conversion 因子的同品類基準）。 */
public record CategorySalesRow(
        Long categoryId,
        String categoryName,
        long totalQty,
        BigDecimal totalRevenue,
        Long totalImpression,
        Double conversionRate) {}
