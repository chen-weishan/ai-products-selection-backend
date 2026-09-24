package com.example.ssds.api.scoring.factor;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.LogisticsCondition;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** §5.2.2 LOGISTICS_RISK：命中條件逐項加總，點數由 risk_rule 輸入。 */
public class LogisticsRiskFactorProvider {
    public FactorComputation provide(
            Set<LogisticsCondition> conditions, Month evaluationMonth, LogisticsRule rule) {
        if (conditions == null || conditions.isEmpty()) {
            return FactorComputation.penalty(
                    FactorCode.LOGISTICS_RISK, null, BigDecimal.ZERO, false, "未提供物流條件");
        }
        if (evaluationMonth == null || rule == null) {
            throw new IllegalArgumentException("評估月份與物流風險規則不可為 null");
        }
        BigDecimal points = BigDecimal.ZERO;
        List<String> hits = new ArrayList<>();
        if ((conditions.contains(LogisticsCondition.CHILLED)
                || conditions.contains(LogisticsCondition.FROZEN))) {
            points = points.add(rule.coldChainPoints());
            hits.add("冷鏈需求");
        }
        if (conditions.contains(LogisticsCondition.MELTABLE) && isSummer(evaluationMonth)) {
            points = points.add(rule.meltableSummerPoints());
            hits.add("夏季易融化");
        }
        if (conditions.contains(LogisticsCondition.FRAGILE)) {
            points = points.add(rule.fragilePoints());
            hits.add("易碎");
        }
        if (conditions.contains(LogisticsCondition.OVERSIZED)) {
            points = points.add(rule.oversizedPoints());
            hits.add("材積異常");
        }
        BigDecimal penalty = points.min(rule.maxPenalty())
                .min(BigDecimal.valueOf(FactorCode.LOGISTICS_RISK.maxPenalty()))
                .setScale(1, RoundingMode.HALF_UP);
        String note = hits.isEmpty() ? "未命中物流風險條件" : "命中：" + String.join("、", hits);
        return FactorComputation.penalty(
                FactorCode.LOGISTICS_RISK, BigDecimal.valueOf(hits.size()), penalty, true, note);
    }

    private static boolean isSummer(Month month) {
        return month.getValue() >= Month.JUNE.getValue()
                && month.getValue() <= Month.SEPTEMBER.getValue();
    }

    public record LogisticsRule(
            BigDecimal meltableSummerPoints,
            BigDecimal coldChainPoints,
            BigDecimal fragilePoints,
            BigDecimal oversizedPoints,
            BigDecimal maxPenalty) {
        public LogisticsRule {
            validate(meltableSummerPoints);
            validate(coldChainPoints);
            validate(fragilePoints);
            validate(oversizedPoints);
            validate(maxPenalty);
        }

        private static void validate(BigDecimal value) {
            if (value == null || value.signum() < 0) {
                throw new IllegalArgumentException("物流風險規則點數不得為 null 或負數");
            }
        }
    }
}
