package com.example.ssds.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 熱度趨勢規則引擎（§5.3.3 斜率計算、§5.8 階段轉換與壽命推估的 RULE 基準層）。
 *
 * <p><b>刻意保持純 Java、不依賴 JPA／資料庫</b>（比照 ssds-core 模組註解「計分邏輯要能
 * 不開資料庫就單元測試」的既定慣例）：呼叫端（ssds-infra 的批次任務）負責從
 * {@code heat_composite_daily} 撈出三個觀測點（t、t-7、t-30）與前一日的
 * stage／stageWeeks，本類別只做純計算。
 *
 * <p><b>⚠️ 待確認：{@link #RISE_THRESHOLD}／{@link #DECLINE_THRESHOLD} 這兩個階段轉換
 * 門檻、以及 {@link #detectDivergence} 的背離定義，是本次補實作時的暫定值。</b>
 * 專案現有檔案（CONTEXT.md、既有程式碼註解）只留下「兩者背離時標記可能見頂，
 * 該由計分引擎決定」這句話（見 {@code TrendQueryDao#findSlopeAnchors} 的 Javadoc），
 * 沒有留下具體數字或判定式，因此下面的門檻是合理但未經規格書 §5.3.3／§5.8
 * 原文核對的猜測，上線前務必對照規格書原文確認、必要時調整。
 *
 * <p>相對地，{@link #estimateLifespanDays} 的壽命對照表是 {@link HeatStage} 類別
 * 註解已經明確記載的數字（RISING 56 天、PLATEAU 42/35 天、DECLINING 17 天），
 * 可以直接視為定案。
 */
public final class HeatTrendCalculator {

    private HeatTrendCalculator() {}

    /**
     * §5.3.3：分母（前一觀測點熱度）為 0 或極小值時的保護值，避免除以 0。
     * 沿用 {@code TrendQueryDao#findSourceBreakdown} 既有查詢使用的 0.01。
     */
    private static final BigDecimal EPSILON = new BigDecimal("0.01");

    /** ⚠️ 暫定門檻，待規格書 §5.8 原文確認。 */
    private static final BigDecimal RISE_THRESHOLD = new BigDecimal("0.05");

    /** ⚠️ 暫定門檻，待規格書 §5.8 原文確認。 */
    private static final BigDecimal DECLINE_THRESHOLD = new BigDecimal("-0.05");

    /**
     * §5.3.3：slope = (heat_t − heat_anchor) / max(heat_anchor, ε)。
     *
     * @return 任一觀測點缺資料時回傳 null（呼叫端應視為「無法計算」而非 0）
     */
    public static BigDecimal slope(BigDecimal heatT, BigDecimal heatAnchor) {
        if (heatT == null || heatAnchor == null) {
            return null;
        }
        BigDecimal denominator = heatAnchor.max(EPSILON);
        return heatT.subtract(heatAnchor).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    /**
     * ⚠️ 暫定規則：slope7d 高於 {@link #RISE_THRESHOLD} 視為上升、低於
     * {@link #DECLINE_THRESHOLD} 視為衰退，介於兩者之間視為高原、且沿用前一日階段
     * （避免門檻邊緣每天來回跳動）。缺前一日資料（新關鍵字第一天）或缺 slope7d
     * 時，退回 RISING 當預設起點。
     */
    public static HeatStage determineStage(BigDecimal slope7d, HeatStage previousStage) {
        if (slope7d == null) {
            return previousStage != null ? previousStage : HeatStage.RISING;
        }
        if (slope7d.compareTo(RISE_THRESHOLD) > 0) {
            return HeatStage.RISING;
        }
        if (slope7d.compareTo(DECLINE_THRESHOLD) < 0) {
            return HeatStage.DECLINING;
        }
        return previousStage != null ? previousStage : HeatStage.PLATEAU;
    }

    /** 階段延續則週數 +1，階段轉換則歸零重算（第 1 週）。 */
    public static short nextStageWeeks(HeatStage previousStage, short previousStageWeeks, HeatStage currentStage) {
        if (previousStage == currentStage) {
            return (short) (previousStageWeeks + 1);
        }
        return 1;
    }

    /**
     * §5.8 各階段初始壽命經驗值（定案，見 {@link HeatStage} 類別註解）：
     * RISING 56 天；PLATEAU 第 1–2 週 42 天、第 3 週以上 35 天；DECLINING 17 天。
     */
    public static int estimateLifespanDays(HeatStage stage, short stageWeeks) {
        return switch (stage) {
            case RISING -> 56;
            case PLATEAU -> stageWeeks <= 2 ? 42 : 35;
            case DECLINING -> 17;
        };
    }

    /**
     * ⚠️ 暫定規則：短期（slope7d）與長期（slope30d）方向相反視為「背離」
     * （可能見頂或止跌訊號）。實際定義待規格書 §5.3.3 原文確認。
     */
    public static boolean detectDivergence(BigDecimal slope7d, BigDecimal slope30d) {
        if (slope7d == null || slope30d == null) {
            return false;
        }
        return slope7d.signum() != 0 && slope30d.signum() != 0 && slope7d.signum() != slope30d.signum();
    }
}
