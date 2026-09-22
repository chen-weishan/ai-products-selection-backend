package com.example.ssds.api.score.dto;

import com.example.ssds.core.domain.FactorCode;
import java.math.BigDecimal;

/** 品項詳情使用的完整加分因子證據。 */
public record ScoreFactorDetailResponse(
        FactorCode factorCode,
        BigDecimal rawValue,
        BigDecimal normalizedValue,
        BigDecimal weight,
        BigDecimal contribution,
        boolean dataAvailable,
        boolean imputed,
        Long drivingKeywordId,
        Long drivingFestivalId,
        String note) {}
