package com.example.ssds.infra.dao.projection;

import java.math.BigDecimal;

/**
 * 因子明細單列（FR-05 區塊 C 加分因子／區塊 D 風險扣分）。
 *
 * <p>畫面上每根長條直接對應一列（§7.2 score_factor）。
 * {@code contribution} 由 SQL 算好（正規化值 × 權重），
 * 讓前端不必重算而導致與後端小數處理不一致。
 */
public record FactorBreakdownRow(
        String factorCode,
        BigDecimal rawValue,
        BigDecimal normalizedValue,
        BigDecimal weight,
        BigDecimal penaltyValue,
        BigDecimal contribution,
        boolean penalty,
        boolean imputed,
        boolean dataAvailable) {}
