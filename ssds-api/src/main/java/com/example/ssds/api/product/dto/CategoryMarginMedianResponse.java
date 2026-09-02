package com.example.ssds.api.product.dto;

import java.math.BigDecimal;

/** S-04 類別毛利率中位數比較資料。 */
public record CategoryMarginMedianResponse(
        Long categoryId,
        String categoryName,
        BigDecimal medianMarginRate,
        long sampleCount
) {}
