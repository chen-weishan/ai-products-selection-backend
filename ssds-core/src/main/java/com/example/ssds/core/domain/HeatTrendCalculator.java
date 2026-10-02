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
 * {@code heat_composite_daily} 撈出三個觀測點（t、t-7、t-30）與同階段的連續天數，
 * 本類別只做純計算。
 *
 * <p>相對地，{@link #estimateLifespanDays} 的壽命對照表是 {@link HeatStage} 類別
 * 註解已經明確記載的數字（RISING 56 天、PLATEAU 42/35 天、DECLINING 17 天），
 * 可以直接視為定案。
 */
public final class HeatTrendCalculator {

    private HeatTrendCalculator() {}

    /**
     * §5.3.3：分母（前一觀測點熱度）為 0 或極小值時的保護值，避免除以 0。
     * 規格定義 ε = 1.0。
     */
    private static final BigDecimal EPSILON = new BigDecimal("1.0");

    private static final BigDecimal RISE_THRESHOLD = new BigDecimal("0.10");

    private static final BigDecimal DECLINE_THRESHOLD = new BigDecimal("-0.10");

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
     * <p>窗口內有資料的天數 &lt; {@link #ANCHOR_MIN_COVERED_DAYS} 回傳 null；
     * 否則取離目標日最近的一天，平手時取較晚（離「現在」較近）的那天。
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
     * §5.8：只以 slope30d 判定階段；大於 +10% 為上升、小於 -10% 為衰退，
     * 邊界值與缺值皆為高原期。規則不沿用前一日階段，也沒有「連續三週成長」條件。
     */
    public static HeatStage determineStage(BigDecimal slope30d) {
        if (slope30d == null) {
            return HeatStage.PLATEAU;
        }
        if (slope30d.compareTo(RISE_THRESHOLD) > 0) {
            return HeatStage.RISING;
        }
        if (slope30d.compareTo(DECLINE_THRESHOLD) < 0) {
            return HeatStage.DECLINING;
        }
        return HeatStage.PLATEAU;
    }

    /** §5.8：stageWeeks = ceil(同階段連續天數 / 7)，當日至少算第 1 天。 */
    public static short stageWeeksFromContinuousDays(int continuousDays) {
        int normalizedDays = Math.max(1, continuousDays);
        return (short) Math.ceil(normalizedDays / 7.0);
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
     * §5.3.3：只有短期轉負、長期仍為正（slope7d &lt; 0 且 slope30d &gt; 0）
     * 才標示可能見頂的背離。
     */
    public static boolean detectDivergence(BigDecimal slope7d, BigDecimal slope30d) {
        if (slope7d == null || slope30d == null) {
            return false;
        }
        return slope7d.signum() < 0 && slope30d.signum() > 0;
    }
}
