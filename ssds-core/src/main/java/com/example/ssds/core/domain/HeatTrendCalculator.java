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
 * <p><b>2026-09-22 依規格書 §5.3.3／§FR-06 原文核對後修正：</b>
 * <ul>
 *   <li>{@link #EPSILON} 原本誤沿用另一支既有查詢的 0.01，規格書 §5.3.3 明寫
 *       {@code ε = 1.0}，已改正。</li>
 *   <li>{@link #detectDivergence} 原本用「兩者正負號不同」判斷背離，但 §5.3.3
 *       原文只定義單一方向（{@code slope_7d < 0} 且 {@code slope_30d > 0}）為
 *       「可能見頂」，已改正為單方向判定。</li>
 *   <li>新增 {@link #trendRaw}：§5.3.3 定義 {@code TREND} 因子的原始值為
 *       {@code trend_raw = 0.7 × slope_7d + 0.3 × slope_30d}，這是雙窗口斜率算出來
 *       之後、餵進選品分數引擎之前的最後一步，先前整個專案沒有任何地方計算這個值。</li>
 * </ul>
 *
 * <p><b>2026-09-22（二次修正）依規格書 v3.0.1 §FR-06「熱度階段的統一詞彙」表
 * 與 E-07／E-08 裁決重寫階段判定：</b>
 * <ul>
 *   <li>{@link #determineStage} 先前用 {@code slope30d > 10%} 且「連續 3 週
 *       合成熱度成長」兩條件同時成立才判 {@code RISING}，且中間地帶會沿用
 *       {@code previousStage}。這與 v3.0.1 裁決不符：判定式改為
 *       {@code slope_30d} 單一變數的三段不等式，門檻對稱 ±10%、三段互斥且窮盡，
 *       {@code PLATEAU} 為唯一預設歸屬，不再受前一日階段或任何額外條件影響——
 *       {@code stage} 必須是當日 {@code slope_30d} 的確定性函數。</li>
 *   <li>「連續 3 週合成熱度成長」從判定門檻**降為顯示屬性**，改由既有的
 *       {@link #stageWeeksFromContinuousDays} 承載（於 UI 顯示「已上升 N 週」
 *       供人判讀強度），不再是 {@link #determineStage} 的參數，原本呼叫端
 *       （{@code HeatCompositeCalibrationService}）組裝「連續 3 週」代理判斷的
 *       邏輯已一併移除。</li>
 *   <li>{@code slope30d == null} 時的預設值由 {@code RISING} 改為
 *       {@code PLATEAU}（安全預設，非捏造成長訊號）。</li>
 *   <li>{@link #nextStageWeeks}（前一日 {@code stageWeeks} 直接 +1）已刪除，
 *       因為每日排程執行一次就會把「執行次數」誤當「週數」累加，改為
 *       {@link #stageWeeksFromContinuousDays}：由呼叫端回溯
 *       {@code heat_composite_daily} 算出「當日往前連續相同 stage 的天數」，
 *       此處只做 {@code ceil(continuousDays / 7.0)} 的純換算。</li>
 * </ul>
 *
 * <p>{@link #estimateLifespanDays} 的壽命對照表是 {@link HeatStage} 類別
 * 註解已經明確記載的數字（RISING 56 天、PLATEAU 42/35 天、DECLINING 17 天），
 * 可以直接視為定案，本次未變動。
 *
 * <p>{@link #resolveAnchor} 的「±3 天容錯窗、窗內需 ≥4 天有資料才採信」是產品面
 * 口頭決議（Threads 這類稀疏來源常整天沒資料，精確比對會讓斜率動不動就是 null；
 * 但只是要看大概走勢，不必要求精確到日），不是規格書原文數字，上線前建議一併
 * 記錄進規格書，避免日後又找不到依據。本次未變動。
 */
public final class HeatTrendCalculator {

    private HeatTrendCalculator() {}

    /**
     * §5.3.3：分母（前一觀測點熱度）為 0 或極小值時的保護值，避免除以 0。
     * 規格書原文明寫 {@code ε = 1.0}（先前誤沿用另一支既有查詢的 0.01，已修正）。
     */
    private static final BigDecimal EPSILON = new BigDecimal("1.0");

    /** §FR-06：PLATEAU／DECLINING 分界，{@code slope_30d < DECLINE_THRESHOLD} 視為衰退。 */
    private static final BigDecimal DECLINE_THRESHOLD = new BigDecimal("-0.10");

    /** §FR-06：{@code slope_30d > RISE_THRESHOLD} 即為 RISING，唯一條件（v3.0.1）。 */
    private static final BigDecimal RISE_THRESHOLD = new BigDecimal("0.10");

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
     * §FR-06「熱度階段的統一詞彙」（v3.0.1）：只依 {@code slope_30d} 單一變數的
     * 三段不等式，門檻對稱 ±10%，三段互斥且窮盡：
     * <ul>
     *   <li>RISING：{@code slope30d > +0.10}</li>
     *   <li>DECLINING：{@code slope30d < −0.10}</li>
     *   <li>PLATEAU：{@code −0.10 ≤ slope30d ≤ +0.10}，含邊界值；
     *       {@code slope30d == null} 時亦歸此（安全預設）。</li>
     * </ul>
     * 階段不受前一日階段影響——{@code stage} 必須是當日 {@code slope_30d} 的
     * 確定性函數。「連續 3 週合成熱度成長」已降為顯示屬性，不在此判定，見
     * {@link #stageWeeksFromContinuousDays}。
     *
     * @param slope30d 30 日斜率（§5.3.3）
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

    /**
     * §FR-06：{@code stage_weeks} 的規則式算法——自當日往前逐日回溯
     * {@code heat_composite_daily}，統計與當日相同 {@code stage} 的連續天數
     * （包含當日在內；資料不連續或階段不同即中斷，不得跨越缺日繼續累計），
     * 再除以 7 無條件進位。
     *
     * <p>本方法只做最後一步純換算；「回溯算出連續天數」由呼叫端負責
     * （{@code HeatCompositeCalibrationService}，需查詢
     * {@code heat_composite_daily} 的歷史列），刻意保持本類別不依賴資料庫。
     *
     * <p>注意：同日重跑必須代入相同的 {@code continuousDays} 才能保持結果一致
     * （即回溯查詢本身要具冪等性，不可把「執行次數」算進連續天數）。
     *
     * @param continuousDays 含當日在內、與當日階段相同的連續天數，至少為 1
     */
    public static short stageWeeksFromContinuousDays(long continuousDays) {
        if (continuousDays < 1) {
            throw new IllegalArgumentException("continuousDays 至少為 1（含當日）：" + continuousDays);
        }
        return (short) Math.ceil(continuousDays / 7.0);
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
     * §5.3.3：{@code slope_7d < 0} 且 {@code slope_30d > 0} 時標記為「可能見頂」。
     * 注意這是單一方向的定義——「7 日轉正、30 日仍負」（止跌訊號）規格書並未
     * 定義為背離，不在此方法的判定範圍內。
     */
    public static boolean detectDivergence(BigDecimal slope7d, BigDecimal slope30d) {
        if (slope7d == null || slope30d == null) {
            return false;
        }
        return slope7d.signum() < 0 && slope30d.signum() > 0;
    }

    /**
     * §5.3.3：{@code TREND} 因子的原始值，雙窗口斜率加權：
     * {@code trend_raw = 0.7 × slope_7d + 0.3 × slope_30d}。
     * 7 日窗權重較高（抓早期上升），30 日窗提供穩定性（避免單日雜訊）。
     * 此值不落地存表（{@code heat_composite_daily} 只存 slope_7d／slope_30d，
     * 見 §7.2.3），由計分引擎在需要 TREND 因子原始值時即時算出。
     *
     * @return 任一斜率缺資料時回傳 null（呼叫端應視為 TREND 因子無資料，見 §5.3.3
     *         「不滿 7 日時整個 TREND 因子標為無資料」）
     */
    public static BigDecimal trendRaw(BigDecimal slope7d, BigDecimal slope30d) {
        if (slope7d == null || slope30d == null) {
            return null;
        }
        return slope7d.multiply(new BigDecimal("0.7"))
                .add(slope30d.multiply(new BigDecimal("0.3")));
    }
}   