package com.example.ssds.api.scoring.factor;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Season;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** §5.2.2 INVENTORY_RISK：效期、季節與 MOQ 命中條件逐項加總。 */
public class InventoryRiskFactorProvider {
    public FactorComputation provide(
            Integer shelfLifeDays, Season season, Integer moq, InventoryRule rule) {
        if (shelfLifeDays == null && season == null && moq == null) {
            return FactorComputation.penalty(
                    FactorCode.INVENTORY_RISK, null, BigDecimal.ZERO, false, "未提供庫存風險資料");
        }
        if (rule == null) throw new IllegalArgumentException("庫存風險規則不可為 null");
        if ((shelfLifeDays != null && shelfLifeDays < 0) || (moq != null && moq < 0)) {
            throw new IllegalArgumentException("效期與 MOQ 不得為負數");
        }
        BigDecimal points = BigDecimal.ZERO;
        List<String> hits = new ArrayList<>();
        if (shelfLifeDays != null && shelfLifeDays < rule.shelfLifeDaysThreshold()) {
            points = points.add(rule.shortShelfLifePoints());
            hits.add("效期短");
        }
        if (season != null && season != Season.ALL) {
            points = points.add(rule.seasonalPoints());
            hits.add("季節性強");
        }
        if (moq != null && moq > rule.moqThreshold()) {
            points = points.add(rule.highMoqPoints());
            hits.add("MOQ 偏高");
        }
        BigDecimal penalty = points.min(rule.maxPenalty())
                .min(BigDecimal.valueOf(FactorCode.INVENTORY_RISK.maxPenalty()))
                .setScale(1, RoundingMode.HALF_UP);
        String note = hits.isEmpty() ? "未命中庫存風險條件" : "命中：" + String.join("、", hits);
        return FactorComputation.penalty(
                FactorCode.INVENTORY_RISK, BigDecimal.valueOf(hits.size()), penalty, true, note);
    }

    public record InventoryRule(
            int shelfLifeDaysThreshold,
            BigDecimal shortShelfLifePoints,
            BigDecimal seasonalPoints,
            int moqThreshold,
            BigDecimal highMoqPoints,
            BigDecimal maxPenalty) {
        public InventoryRule {
            if (shelfLifeDaysThreshold < 0 || moqThreshold < 0) {
                throw new IllegalArgumentException("庫存風險門檻不得為負數");
            }
            validate(shortShelfLifePoints);
            validate(seasonalPoints);
            validate(highMoqPoints);
            validate(maxPenalty);
        }

        private static void validate(BigDecimal value) {
            if (value == null || value.signum() < 0) {
                throw new IllegalArgumentException("庫存風險規則點數不得為 null 或負數");
            }
        }
    }
}
