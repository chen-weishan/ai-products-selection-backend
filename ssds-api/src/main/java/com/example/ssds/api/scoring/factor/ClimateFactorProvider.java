package com.example.ssds.api.scoring.factor;

import com.example.ssds.core.domain.FactorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** FR-17-2 CLIMATE：只使用歷史同期月均溫，不接受短期預報。 */
public class ClimateFactorProvider {
    public FactorComputation provide(ClimateEvidence evidence, PercentileBasis basis) {
        if (evidence == null || evidence.historicalAverageTemperature() == null) {
            return FactorComputation.unavailableBonus(FactorCode.CLIMATE, "缺少歷史同期月均溫");
        }
        boolean hasProductRange = evidence.productMin() != null && evidence.productMax() != null;
        BigDecimal min = hasProductRange ? evidence.productMin() : evidence.categoryMin();
        BigDecimal max = hasProductRange ? evidence.productMax() : evidence.categoryMax();
        if (min == null || max == null) {
            return FactorComputation.unavailableBonus(FactorCode.CLIMATE, "品項與品類皆未設定適溫區間");
        }
        if (max.compareTo(min) < 0) throw new IllegalArgumentException("適溫上限不得小於下限");
        if (evidence.tolerance() == null || evidence.tolerance().signum() <= 0) {
            throw new IllegalArgumentException("氣候容忍範圍必須大於 0");
        }
        BigDecimal temperature = evidence.historicalAverageTemperature();
        BigDecimal distance = temperature.compareTo(min) < 0
                ? min.subtract(temperature)
                : temperature.compareTo(max) > 0 ? temperature.subtract(max) : BigDecimal.ZERO;
        BigDecimal fit = BigDecimal.ONE
                .subtract(distance.divide(evidence.tolerance(), 6, RoundingMode.HALF_UP))
                .max(BigDecimal.ZERO)
                .setScale(6, RoundingMode.HALF_UP);
        boolean fallback = !hasProductRange;
        return FactorComputation.bonus(
                FactorCode.CLIMATE,
                fit,
                basis,
                fallback,
                fallback ? "沿用品類預設適溫區間" : null);
    }

    public record ClimateEvidence(
            BigDecimal historicalAverageTemperature,
            BigDecimal productMin,
            BigDecimal productMax,
            BigDecimal categoryMin,
            BigDecimal categoryMax,
            BigDecimal tolerance) {}
}
