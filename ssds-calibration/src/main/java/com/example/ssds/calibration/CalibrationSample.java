package com.example.ssds.calibration;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import java.util.Map;

/**
 * 一筆校準樣本：已回填結果的決策，配上決策當下綁定的那筆評分快照（§FR-15、AC-11-6）。
 *
 * <p>{@code normalizedValues} 只放 {@code data_available = true} 的加分因子；
 * 缺值因子不放進來，重算時其權重按比例分攤給其餘因子（§5.7）。
 *
 * @param sceneType 綁定評分的情境（決策綁主情境，§FR-04）
 * @param penaltySubtotal 原評分的扣分小計。扣分固定生效、不參與權重（§5.2.2），回測直接沿用
 * @param actualQty 標籤：{@code campaign_result.actual_qty}（§FR-15「實際銷量」）
 * @param hit 售罄狀況為 EARLY_SELLOUT 或 ON_TIME（§FR-11-3 A 級達標的定義）
 */
public record CalibrationSample(
        long decisionId,
        SceneType sceneType,
        Map<FactorCode, Double> normalizedValues,
        double penaltySubtotal,
        double actualQty,
        boolean hit) {

    public CalibrationSample {
        normalizedValues = Map.copyOf(normalizedValues);
    }
}
