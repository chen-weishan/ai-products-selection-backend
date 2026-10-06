package com.example.ssds.calibration;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import java.util.EnumMap;
import java.util.Map;

/**
 * 一組可回測的評分規則：四榜權重＋四榜分級門檻（§FR-08 版本化的兩樣東西）。
 *
 * <p>可以是既有 {@code weight_version}，也可以是尚未落成版本的校準建議或平權基準。
 *
 * @param code 穩定代碼（EQUAL／CURRENT／SUGGESTED／VERSION），給 AI 與前端辨識用
 * @param label 畫面顯示名稱
 * @param versionId 對應的 {@code weight_version.id}；非版本者為 null
 */
public record WeightScheme(
        String code,
        String label,
        Long versionId,
        Map<SceneType, Map<FactorCode, Double>> weights,
        Map<SceneType, GradeCut> thresholds) {

    /** 分級門檻（§5.6）。 */
    public record GradeCut(double gradeAMin, double gradeBMin) {
    }

    /** 平權基準：六個加分因子各 1/6，門檻沿用傳入者（通常是現行版本）。 */
    public static WeightScheme equalWeights(String label, Map<SceneType, GradeCut> thresholds) {
        Map<FactorCode, Double> equal = new EnumMap<>(FactorCode.class);
        for (FactorCode code : FactorStatistics.BONUS_FACTORS) {
            equal.put(code, 1.0 / FactorStatistics.BONUS_FACTORS.size());
        }
        Map<SceneType, Map<FactorCode, Double>> weights = new EnumMap<>(SceneType.class);
        for (SceneType scene : SceneType.values()) {
            weights.put(scene, equal);
        }
        return new WeightScheme("EQUAL", label, null, weights, thresholds);
    }
}
