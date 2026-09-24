package com.example.ssds.core.scoring;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Grade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

/** v3.0 §5.5–§5.7 的純計分邏輯，不依賴資料庫或 LLM。 */
public class ScoringEngine {
    private static final FactorCode[] BONUS_FACTORS = {
        FactorCode.TREND,
        FactorCode.MARGIN,
        FactorCode.CVR,
        FactorCode.PRICE_FIT,
        FactorCode.FESTIVAL,
        FactorCode.CLIMATE
    };

    public Result calculate(
            Map<FactorCode, FactorValue> factors,
            Map<FactorCode, BigDecimal> configuredWeights,
            BigDecimal gradeAMin,
            BigDecimal gradeBMin) {
        EnumMap<FactorCode, BigDecimal> effectiveWeights = new EnumMap<>(FactorCode.class);
        BigDecimal availableWeight = BigDecimal.ZERO;
        int availableCount = 0;
        for (FactorCode code : BONUS_FACTORS) {
            FactorValue factor = factors.get(code);
            BigDecimal weight = configuredWeights.get(code);
            if (weight == null) throw new IllegalArgumentException("缺少權重：" + code);
            if (factor != null && factor.dataAvailable() && factor.normalizedValue() != null) {
                availableWeight = availableWeight.add(weight);
                availableCount++;
            }
        }
        if (availableCount < 3) {
            throw new InsufficientScoringDataException("六項加分因子缺少四項以上，無法產生分數");
        }
        if (availableWeight.signum() <= 0) throw new IllegalArgumentException("可用因子的權重總和必須大於 0");

        BigDecimal effectiveSum = BigDecimal.ZERO;
        FactorCode lastAvailable = null;
        for (FactorCode code : BONUS_FACTORS) {
            FactorValue factor = factors.get(code);
            if (factor == null || !factor.dataAvailable() || factor.normalizedValue() == null) {
                effectiveWeights.put(code, BigDecimal.ZERO.setScale(6));
                continue;
            }
            BigDecimal effective = configuredWeights.get(code)
                    .divide(availableWeight, 6, RoundingMode.HALF_UP);
            effectiveWeights.put(code, effective);
            effectiveSum = effectiveSum.add(effective);
            lastAvailable = code;
        }
        BigDecimal residual = BigDecimal.ONE.subtract(effectiveSum);
        effectiveWeights.put(lastAvailable, effectiveWeights.get(lastAvailable).add(residual));

        BigDecimal bonus = BigDecimal.ZERO;
        for (FactorCode code : BONUS_FACTORS) {
            FactorValue factor = factors.get(code);
            if (factor != null && factor.dataAvailable() && factor.normalizedValue() != null) {
                bonus = bonus.add(factor.normalizedValue().multiply(effectiveWeights.get(code)));
            }
        }
        bonus = bonus.setScale(2, RoundingMode.HALF_UP);

        BigDecimal penalty = factors.entrySet().stream()
                .filter(entry -> entry.getKey().isPenalty())
                .map(Map.Entry::getValue)
                .filter(value -> value != null && value.penaltyValue() != null)
                .map(FactorValue::penaltyValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .min(BigDecimal.valueOf(FactorCode.PENALTY_CAP))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal finalScore = bonus.subtract(penalty).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        Grade grade = finalScore.compareTo(gradeAMin) >= 0
                ? Grade.A
                : finalScore.compareTo(gradeBMin) >= 0 ? Grade.B : Grade.C;
        if (grade == Grade.A
                && penalty.compareTo(BigDecimal.valueOf(FactorCode.PENALTY_GRADE_SUPPRESS_THRESHOLD)) >= 0) {
            grade = Grade.B;
        }
        return new Result(bonus, penalty, finalScore, grade, Map.copyOf(effectiveWeights));
    }

    public record FactorValue(
            BigDecimal normalizedValue, BigDecimal penaltyValue, boolean dataAvailable) {}

    public record Result(
            BigDecimal bonusSubtotal,
            BigDecimal penaltySubtotal,
            BigDecimal finalScore,
            Grade grade,
            Map<FactorCode, BigDecimal> effectiveWeights) {}

    public static class InsufficientScoringDataException extends RuntimeException {
        public InsufficientScoringDataException(String message) {
            super(message);
        }
    }
}
