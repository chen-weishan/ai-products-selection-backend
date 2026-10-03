package com.example.ssds.calibration;

import com.example.ssds.core.domain.FactorCode;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 部分採納（AC-15-5）：只接受勾選因子的建議權重，其餘因子等比例縮放補足到 1。
 *
 * <p>「等比例」取自示意圖 S-19 的 AI 解讀範例（熱度調升後「其餘因子等比例調降以維持合計 100%」）。
 * 勾選單位是因子而非「榜 × 因子」：S-19 因子預測力表一個因子一列，勾選即套用到四榜。
 * 這是設計決定，規格只寫「逐項勾選」。
 */
public final class WeightAdjustments {

    private WeightAdjustments() {
    }

    /**
     * @param current 單一榜的現行權重（加總 1.000）
     * @param suggested 同一榜的建議權重（加總 1.000）
     * @param accepted 接受的因子；全選時結果等於 {@code suggested}
     * @return 小數三位、加總恰為 1.000 的新權重
     */
    public static Map<FactorCode, BigDecimal> applyAccepted(
            Map<FactorCode, BigDecimal> current,
            Map<FactorCode, BigDecimal> suggested,
            Set<FactorCode> accepted) {
        double acceptedSum = 0;
        double untouchedSum = 0;
        for (FactorCode code : current.keySet()) {
            if (accepted.contains(code)) {
                acceptedSum += value(suggested, code);
            } else {
                untouchedSum += value(current, code);
            }
        }
        double remaining = Math.max(0, 1 - acceptedSum);

        Map<FactorCode, Double> result = new EnumMap<>(FactorCode.class);
        for (FactorCode code : current.keySet()) {
            if (accepted.contains(code)) {
                result.put(code, value(suggested, code));
            } else if (untouchedSum > 0) {
                result.put(code, value(current, code) * remaining / untouchedSum);
            } else {
                // 未勾選者原本全為 0，剩餘量無處可分：交給 toThousandths 把勾選者正規化回 1
                result.put(code, 0.0);
            }
        }
        double total = result.values().stream().mapToDouble(Double::doubleValue).sum();
        return total <= 0 ? Map.copyOf(current) : WeightRounding.toThousandths(result);
    }

    private static double value(Map<FactorCode, BigDecimal> weights, FactorCode code) {
        BigDecimal weight = weights.get(code);
        return weight == null ? 0 : weight.doubleValue();
    }
}
