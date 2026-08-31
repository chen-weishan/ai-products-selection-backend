package com.example.ssds.ai.model;

import java.math.BigDecimal;
import java.util.List;

/** 僅包含彙總統計，不允許逐筆銷售或開團紀錄。 */
public record WeightCalibrationInput(
        String quarter,
        int sampleSize,
        String regressionMethod,
        List<FactorStatistic> factors,
        String regressionNote,
        OverrideStatistics sceneOverrides,
        List<BacktestStatistic> backtests,
        String backtestNote) {
    public record FactorStatistic(
            String factorCode,
            BigDecimal correlation,
            BigDecimal currentWeight,
            BigDecimal suggestedWeight,
            BigDecimal pValue) {}
    public record OverrideStatistics(
            int totalClassifications,
            int overrideCount,
            BigDecimal overrideRate,
            List<CategoryOverrideStatistic> concentratedCategories) {}
    public record CategoryOverrideStatistic(
            String category,
            int totalClassifications,
            int overrideCount,
            BigDecimal overrideRate) {}
    public record BacktestStatistic(
            String scheme,
            BigDecimal correlation,
            BigDecimal gradeAHitRate) {}
}
