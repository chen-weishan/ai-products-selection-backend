package com.example.ssds.infra.dao.projection;

import java.math.BigDecimal;

/**
 * 季節不匹配示警偵測的一列：某品項<b>最新一次評分</b>的 {@code CLIMATE} 因子。
 *
 * @param status         {@code product.status} 的字串值
 * @param percentile     {@code score_factor.normalized_value}，即氣候適配 {@code fit} 的同品類百分位（0–100）；
 *                       因子無資料時為 null
 * @param dataAvailable  {@code score_factor.data_available}；FALSE 表該因子無資料（§5.7），不可判定
 */
public record SeasonAlertRow(
        Long productId,
        Long categoryId,
        String status,
        BigDecimal percentile,
        boolean dataAvailable) {
}
