package com.example.ssds.api.product.dto;

import java.math.BigDecimal;

/** 品項節慶關聯度回應。 */
public record ProductFestivalAffinityResponse(
        String festivalCode,
        String festivalName,
        BigDecimal affinity
) {
}
