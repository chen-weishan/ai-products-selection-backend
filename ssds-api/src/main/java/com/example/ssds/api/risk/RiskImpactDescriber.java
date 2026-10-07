package com.example.ssds.api.risk;

import java.math.BigDecimal;

import com.example.ssds.core.domain.ProductStatus;

/**
 * S-11「影響」欄的文字。純函式，不查資料庫；規格書沒有定義這一欄，
 * 文字依畫面稿（《畫面功能示意圖》S-11）的範例對應各示警類型。
 */
public final class RiskImpactDescriber {

    private RiskImpactDescriber() {}

    /**
     * @param factorPenalty 該類型對應因子的扣分（僅 REVIEW／LOGISTICS／INVENTORY 使用），查不到評分時為 null
     * @param penaltySubtotal 該品項最新評分的扣分小計，查不到評分時為 null
     */
    public static String describe(
            String riskType, ProductStatus productStatus, BigDecimal factorPenalty, BigDecimal penaltySubtotal) {
        return switch (riskType) {
            case RiskTypes.REVIEW_RISK, RiskTypes.LOGISTICS_RISK, RiskTypes.INVENTORY_RISK ->
                    penaltyImpact(factorPenalty, penaltySubtotal);
            case RiskTypes.PENALTY_CAP -> "分級鎖定最高 B（§FR-04）";
            case RiskTypes.HEAT_CRASH -> statusLabel(productStatus) + "品項熱度急墜";
            case RiskTypes.HEAT_SURGE -> "熱度明顯高於同品類";
            case RiskTypes.SEASON_MISMATCH -> statusLabel(productStatus) + "品項季節錯位";
            case RiskTypes.FESTIVAL_WINDOW_CLOSING -> "尚未建立決策";
            case RiskTypes.LOW_CONFIDENCE -> "分數僅供參考";
            case RiskTypes.DATA_INSUFFICIENT -> "資料不足，無法產生分數";
            default -> "";
        };
    }

    private static String penaltyImpact(BigDecimal factorPenalty, BigDecimal penaltySubtotal) {
        if (factorPenalty == null) {
            return "計入風險扣分";
        }
        String text = "扣 " + factorPenalty.stripTrailingZeros().toPlainString() + " 分";
        if (penaltySubtotal == null) {
            return text;
        }
        return penaltySubtotal.compareTo(RiskAlertRuleService.DEFAULT_PENALTY_CAP_THRESHOLD) >= 0
                ? text + "（扣分小計 ≥ 20，已壓級）"
                : text + "（扣分小計 < 20，未壓級）";
    }

    private static String statusLabel(ProductStatus status) {
        if (status == ProductStatus.LISTED) {
            return "已上架";
        }
        if (status == ProductStatus.ADOPTED) {
            return "已採納";
        }
        return "";
    }
}
