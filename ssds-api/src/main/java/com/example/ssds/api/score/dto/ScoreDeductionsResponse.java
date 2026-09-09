package com.example.ssds.api.score.dto;

import java.math.BigDecimal;
import java.util.List;

import com.example.ssds.core.domain.FactorCode;

/**
 * 扣分明細（規格書 §FR-04「扣分明細以獨立卡片呈現」、§5.6）。
 *
 * <p>扣分因子固定生效、不參與權重調整（§5.2.2），所以本回應裡沒有 weight，
 * 也沒有 normalizedValue——扣分不做百分位換算。
 *
 * <p>{@code penaltySubtotal} 為 <b>正值</b>（AC-04-7），負號由 UI 加。
 *
 * <p><b>不要用 {@code items} 的加總取代 {@code penaltySubtotal}。</b>
 * 兩者不保證一致：分數可能有扣分小計卻沒有任何因子明細列。
 * 小計一律直接取 {@code product_score.penalty_subtotal}。
 */
public record ScoreDeductionsResponse(
        Long scoreId,

        /** 扣分小計，0–40 的正值。 */
        BigDecimal penaltySubtotal,

        /**
         * §5.6 硬規則：扣分達 20（含）以上時分級最高只給 B，並強制進入
         * FR-10 風險示警清單。門檻值屬於業務規則，由後端判斷後回布林，
         * 不要讓前端自己寫 {@code penaltySubtotal >= 20}。
         */
        boolean riskSuppressed,

        /** 三個扣分因子各一筆。可能為空清單（見上方說明），不是 null。 */
        List<DeductionItem> items) {

    /**
     * 單一扣分因子。
     *
     * <p>兩個欄位都可能是 null，前端不能當 0 顯示：
     * <ul>
     *   <li>{@code penaltyValue}——{@code dataAvailable = false} 時為 null
     *       （§5.7：沒資料就不扣分，不是扣 0 分）</li>
     *   <li>{@code rawValue}——不是每個因子都有原始值可供顯示</li>
     * </ul>
     */
    public record DeductionItem(
            FactorCode factorCode,

            /** 實際扣掉的分數，正值。無資料時為 null。 */
            BigDecimal penaltyValue,

            /** 判定依據的原始值，如負評率 0.22。可為 null。 */
            BigDecimal rawValue,

            /**
             * false 代表這項風險沒有資料可判定，因此<b>不扣分</b>（§5.7），
             * UI 應標示為「未評估」而非「無風險」。
             */
            boolean dataAvailable) {
    }
}
