package com.example.ssds.api.scoring.factor;

import com.example.ssds.core.domain.FactorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** §5.2.2 REVIEW_RISK：由已保存的評論分析統計以固定分段規則扣分。 */
public class ReviewRiskFactorProvider {
    public FactorComputation provide(ReviewEvidence evidence) {
        if (evidence == null) {
            return FactorComputation.penalty(
                    FactorCode.REVIEW_RISK, null, BigDecimal.ZERO, false, "評論樣本不足 20 則");
        }
        if (evidence.negativeCount() < 0 || evidence.riskTopicNegativeCount() < 0
                || evidence.totalCount() < 0
                || evidence.negativeCount() > evidence.totalCount()
                || evidence.riskTopicNegativeCount() > evidence.negativeCount()) {
            throw new IllegalArgumentException("評論統計數量不合法");
        }
        if (evidence.totalCount() < evidence.minSampleSize()) {
            return FactorComputation.penalty(
                    FactorCode.REVIEW_RISK,
                    null,
                    BigDecimal.ZERO,
                    false,
                    "評論樣本不足 " + evidence.minSampleSize() + " 則");
        }
        BigDecimal negativeRate = ratio(evidence.negativeCount(), evidence.totalCount());
        BigDecimal topicShare = evidence.negativeCount() == 0
                ? BigDecimal.ZERO
                : ratio(evidence.riskTopicNegativeCount(), evidence.negativeCount());
        BigDecimal penalty;
        if (negativeRate.compareTo(evidence.categoryNegativeRateThreshold()) <= 0) {
            penalty = BigDecimal.ZERO;
        } else if (topicShare.compareTo(new BigDecimal("0.3")) < 0) {
            penalty = new BigDecimal("8");
        } else if (topicShare.compareTo(new BigDecimal("0.6")) < 0) {
            penalty = new BigDecimal("14");
        } else {
            penalty = BigDecimal.valueOf(FactorCode.REVIEW_RISK.maxPenalty());
        }
        String note = "負評率 " + negativeRate.setScale(3, RoundingMode.HALF_UP)
                + "，風險主題占比 " + topicShare.setScale(3, RoundingMode.HALF_UP);
        return FactorComputation.penalty(
                FactorCode.REVIEW_RISK, negativeRate, penalty.setScale(1), true, note);
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    public record ReviewEvidence(
            int totalCount,
            int negativeCount,
            int riskTopicNegativeCount,
            BigDecimal categoryNegativeRateThreshold,
            int minSampleSize) {
        public ReviewEvidence {
            if (categoryNegativeRateThreshold == null
                    || categoryNegativeRateThreshold.signum() < 0
                    || categoryNegativeRateThreshold.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("品類負評率門檻必須介於 0 到 1");
            }
            if (minSampleSize < 1) throw new IllegalArgumentException("評論最小樣本數必須大於 0");
        }

        public ReviewEvidence(
                int totalCount,
                int negativeCount,
                int riskTopicNegativeCount,
                BigDecimal categoryNegativeRateThreshold) {
            this(totalCount, negativeCount, riskTopicNegativeCount, categoryNegativeRateThreshold, 20);
        }
    }
}
