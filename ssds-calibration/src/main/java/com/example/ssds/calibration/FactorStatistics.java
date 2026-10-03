package com.example.ssds.calibration;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * §FR-15 步驟 1：六個加分因子與實際銷量的相關分析，並由統計結果產生建議權重。
 *
 * <p>AC-15-2：建議權重只在這裡產生，AI（Agent 7）僅解讀，不產生數值。
 *
 * <p><b>建議權重演算法（設計決定：規格只寫「迴歸分析產出相關係數」，
 * 未定義如何轉成權重）</b>：
 * <pre>
 *   α      = min(1, n / CALIBRATION_MIN_SAMPLE)          樣本越少調整越小
 *   r̄      = 樣本充足因子的相關係數平均
 *   m_i    = max(0, 1 + α·(r_i − r̄))                     樣本不足的因子 m_i = 1
 *   w'_s,i = w_s,i · m_i / Σ_j w_s,j · m_j               每榜各自正規化回 1
 * </pre>
 * 相關係數用全部樣本合併計算，再乘到四榜各自的現行權重上：
 * 四榜的權重形狀（例如話題爆款榜重熱度）得以保留，不會被拉成同一組；
 * 單榜樣本在專題期間只有個位數，分榜計算沒有統計意義。
 * 不用多元 OLS：樣本數百筆以下時六個係數極不穩定且常出現負值。
 */
public final class FactorStatistics {

    public static final String METHOD = "spearman-tilt";

    public static final List<FactorCode> BONUS_FACTORS = Arrays.stream(FactorCode.values())
            .filter(code -> !code.isPenalty())
            .toList();

    private FactorStatistics() {
    }

    /**
     * @param currentWeights 現行版本四榜權重（每榜六因子，加總 1.000）
     * @param minSample {@code CALIBRATION_MIN_SAMPLE}，決定收縮係數 α
     * @param minFactorSample 單一因子有效樣本低於此數即視為樣本不足，不參與調整
     */
    public static Result analyze(
            List<CalibrationSample> samples,
            Map<SceneType, Map<FactorCode, BigDecimal>> currentWeights,
            int minSample,
            int minFactorSample) {
        List<FactorResult> factors = new ArrayList<>();
        for (FactorCode code : BONUS_FACTORS) {
            factors.add(correlate(samples, code, minFactorSample));
        }

        double shrinkage = minSample <= 0 ? 1 : Math.min(1, (double) samples.size() / minSample);
        double meanCorrelation = factors.stream()
                .filter(FactorResult::sufficient)
                .mapToDouble(FactorResult::correlation)
                .average()
                .orElse(0);

        Map<FactorCode, Double> multipliers = new EnumMap<>(FactorCode.class);
        for (FactorResult factor : factors) {
            double m = factor.sufficient()
                    ? Math.max(0, 1 + shrinkage * (factor.correlation() - meanCorrelation))
                    : 1;
            multipliers.put(factor.code(), m);
        }

        Map<SceneType, Map<FactorCode, BigDecimal>> suggested = new EnumMap<>(SceneType.class);
        for (Map.Entry<SceneType, Map<FactorCode, BigDecimal>> scene : currentWeights.entrySet()) {
            suggested.put(scene.getKey(), tilt(scene.getValue(), multipliers));
        }
        return new Result(METHOD, samples.size(), minSample, minFactorSample, shrinkage,
                meanCorrelation, List.copyOf(factors), currentWeights, suggested);
    }

    private static FactorResult correlate(List<CalibrationSample> samples, FactorCode code, int minFactorSample) {
        List<CalibrationSample> withValue = samples.stream()
                .filter(sample -> sample.normalizedValues().containsKey(code))
                .toList();
        double[] x = withValue.stream().mapToDouble(sample -> sample.normalizedValues().get(code)).toArray();
        double[] y = withValue.stream().mapToDouble(CalibrationSample::actualQty).toArray();
        RankCorrelation.Result result = RankCorrelation.of(x, y);
        boolean sufficient = result != null && withValue.size() >= minFactorSample;
        return new FactorResult(code, withValue.size(),
                result == null ? null : result.correlation(),
                result == null ? null : result.pValue(),
                sufficient);
    }

    private static Map<FactorCode, BigDecimal> tilt(
            Map<FactorCode, BigDecimal> current, Map<FactorCode, Double> multipliers) {
        Map<FactorCode, Double> tilted = new EnumMap<>(FactorCode.class);
        double sum = 0;
        for (Map.Entry<FactorCode, BigDecimal> entry : current.entrySet()) {
            double value = entry.getValue().doubleValue() * multipliers.getOrDefault(entry.getKey(), 1.0);
            tilted.put(entry.getKey(), value);
            sum += value;
        }
        // 所有有權重的因子都被壓成 0（極端負相關）時無法正規化，維持現行
        return sum <= 0 ? Map.copyOf(current) : WeightRounding.toThousandths(tilted);
    }

    /**
     * @param n 有該因子資料的樣本數
     * @param correlation Spearman 相關係數；樣本不足 3 筆或無變異時為 null
     * @param sufficient n ≥ minFactorSample 且相關係數有定義；false 者建議權重不動
     */
    public record FactorResult(FactorCode code, int n, Double correlation, Double pValue, boolean sufficient) {
    }

    public record Result(
            String method,
            int sampleSize,
            int minSample,
            int minFactorSample,
            double shrinkage,
            double meanCorrelation,
            List<FactorResult> factors,
            Map<SceneType, Map<FactorCode, BigDecimal>> currentWeights,
            Map<SceneType, Map<FactorCode, BigDecimal>> suggestedWeights) {
    }
}
