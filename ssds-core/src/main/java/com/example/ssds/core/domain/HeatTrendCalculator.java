package com.example.ssds.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Map;

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
 * 該由計分引擎決定」這句話（見 {@code TrendQueryDao#findCompositeSeries} 的 Javadoc），
 * 沒有留下具體數字或判定式，因此下面的門檻是合理但未經規格書 §5.3.3／§5.8
 * 原文核對的猜測，上線前務必對照規格書原文確認、必要時調整。
 *
 * <p>相對地，{@link #estimateLifespanDays} 的壽命對照表是 {@link HeatStage} 類別
 * 註解已經明確記載的數字（RISING 56 天、PLATEAU 42/35 天、DECLINING 17 天），
 * 可以直接視為定案。
 *
 * <p><b>⚠️ 2026-09-18 補充：{@link #resolveAnchor} 的「±3 天容錯窗、窗內需
 * ≥4 天有資料才採信」是產品面口頭決議（Threads 這類稀疏來源常整天沒資料，
 * 精確比對會讓斜率動不動就是 null；但只是要看大概走勢，不必要求精確到日），
 * 同樣不是規格書原文數字，上線前建議一併記錄進規格書，避免日後又找不到
 * 依據。</b>
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

    /** §5.3.3 觀測點容錯窗半徑（天）：目標日前後各 3 天，共 7 天窗口。 */
    private static final long ANCHOR_WINDOW_RADIUS_DAYS = 3;

    /**
     * 7 天窗口內至少要有這麼多天有資料，才信任容錯後取到的值；
     * 未達門檻視為「這段期間資料太稀疏、無法判斷走勢」，直接回傳 null
     * （而不是硬湊一個可能失真的數字）。
     */
    private static final long ANCHOR_MIN_COVERED_DAYS = 4;

    /**
     * §5.3.3：在目標日 ±{@link #ANCHOR_WINDOW_RADIUS_DAYS} 天的窗口內，
     * 找出可信任的觀測值來代表「目標日」。
     *
     * <p>規則（只是要看大概走勢，不要求精確到日）：
     * <ol>
     *   <li>窗口（含目標日共 7 天）內有資料的天數 &lt; {@link #ANCHOR_MIN_COVERED_DAYS}
     *       →判定「這段期間資料太稀疏」，回傳 null（呼叫端 {@link #slope} 會因此回傳 null，
     *       這是刻意的：與其用稀疏資料湊出一個可能失真的斜率，不如老實顯示「無法判斷」）。</li>
     *   <li>否則，取窗口內離目標日最近的一天；若有平手（例如剛好前後各 3 天都有資料），
     *       取離「現在」較近的那天，讓數字盡量貼近最新狀況。</li>
     * </ol>
     *
     * @param series 呼叫端已撈出的原始日序列（見 {@code TrendQueryDao#findCompositeSeries}），
     *               至少要涵蓋 [target-3, target+3] 這段範圍，否則覆蓋率會被低估
     * @param target 要代表的目標日（例如 t-7 或 t-30）
     * @return 窗口內可信任的觀測值；資料太稀疏時回傳 null
     */
    public static BigDecimal resolveAnchor(Map<LocalDate, BigDecimal> series, LocalDate target) {
        if (series == null || target == null) {
            return null;
        }
        LocalDate windowFrom = target.minusDays(ANCHOR_WINDOW_RADIUS_DAYS);
        LocalDate windowTo = target.plusDays(ANCHOR_WINDOW_RADIUS_DAYS);

        var covered = series.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .filter(e -> !e.getKey().isBefore(windowFrom) && !e.getKey().isAfter(windowTo))
                .toList();

        if (covered.size() < ANCHOR_MIN_COVERED_DAYS) {
            return null;
        }

        return covered.stream()
                .min(Comparator
                        .comparing((Map.Entry<LocalDate, BigDecimal> e) ->
                                Math.abs(ChronoUnit.DAYS.between(target, e.getKey())))
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

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
