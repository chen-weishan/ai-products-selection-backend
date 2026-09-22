package com.example.ssds.api.festival.dto;

import java.math.BigDecimal;

/** 品類預設適溫區間（S-20 標記 5、§FR-17-2）。 */
public record CategoryClimateProfileResponse(
        Long categoryId,
        String categoryName,
        BigDecimal idealTempMin,
        BigDecimal idealTempMax,
        BigDecimal tolerance) {
}
