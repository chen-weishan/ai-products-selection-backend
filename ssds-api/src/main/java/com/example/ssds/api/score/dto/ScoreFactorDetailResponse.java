package com.example.ssds.api.score.dto;

import com.example.ssds.core.domain.FactorCode;
import java.math.BigDecimal;

/**
 * 品項詳情使用的完整加分因子證據。
 *
 * <p>{@code drivingKeyword}／{@code drivingFestivalName}：生效標的名稱，語意同
 * {@link ScoreFactorBarResponse}（AC-17-6「於 UI 標示生效節慶」）。
 */
public record ScoreFactorDetailResponse(
        FactorCode factorCode,
        BigDecimal rawValue,
        BigDecimal normalizedValue,
        BigDecimal weight,
        BigDecimal contribution,
        boolean dataAvailable,
        boolean imputed,
        Long drivingKeywordId,
        String drivingKeyword,
        Long drivingFestivalId,
        String drivingFestivalName,
        String note) {}
