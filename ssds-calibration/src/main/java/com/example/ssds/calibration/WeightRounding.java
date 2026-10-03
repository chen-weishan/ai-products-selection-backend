package com.example.ssds.calibration;

import com.example.ssds.core.domain.FactorCode;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 把一組加總約為 1 的權重捨入到小數三位，且加總恰為 1.000。
 *
 * <p>{@code weight_profile.weight} 是 DECIMAL(4,3)，FR-08 存檔與核准都驗
 * 「加總 = 1.000」（AC-08-1）。逐項四捨五入可能加總成 0.999 或 1.001，
 * 所以用最大餘數法：先全部無條件捨去，再把差額的千分位依餘數大小逐一補回。
 */
public final class WeightRounding {

    private static final int UNITS = 1000;

    private WeightRounding() {
    }

    public static Map<FactorCode, BigDecimal> toThousandths(Map<FactorCode, Double> weights) {
        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) {
            throw new IllegalArgumentException("權重加總必須大於 0");
        }
        Map<FactorCode, Integer> units = new EnumMap<>(FactorCode.class);
        Map<FactorCode, Double> remainders = new EnumMap<>(FactorCode.class);
        int assigned = 0;
        for (Map.Entry<FactorCode, Double> entry : weights.entrySet()) {
            double exact = entry.getValue() / sum * UNITS;
            int floor = (int) Math.floor(exact + 1e-9);
            units.put(entry.getKey(), floor);
            remainders.put(entry.getKey(), exact - floor);
            assigned += floor;
        }
        // 同餘數時依 FactorCode 宣告順序，結果才可重現
        List<FactorCode> order = remainders.entrySet().stream()
                .sorted(Map.Entry.<FactorCode, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();
        for (int i = 0; assigned < UNITS; i++, assigned++) {
            FactorCode code = order.get(i % order.size());
            units.merge(code, 1, Integer::sum);
        }
        Map<FactorCode, BigDecimal> result = new EnumMap<>(FactorCode.class);
        units.forEach((code, value) -> result.put(code, BigDecimal.valueOf(value, 3)));
        return result;
    }
}
