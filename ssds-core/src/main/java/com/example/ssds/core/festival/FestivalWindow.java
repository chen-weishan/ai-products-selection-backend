package com.example.ssds.core.festival;

import java.math.BigDecimal;

/**
 * 節慶時間窗函數（規格書 §FR-17-1）。
 *
 * <p>節慶不是固定的加分欄位，而是隨評估日變動的時間窗：月餅在七月分數要高
 * （進入備貨期），在十月要歸零（節慶已過）。
 *
 * <p>v2.0 用「~」表示區間、端點歸屬未定義；v3.0 改寫為四段互斥且涵蓋全數線的
 * 不等式，{@code d = L} 與 {@code d = L + 30} 都歸在黃金備貨期。
 */
public final class FestivalWindow {

    /** 黃金備貨期自前置天數起算的長度（天）。§FR-17-1 的 {@code L + 30}。 */
    public static final int GOLDEN_WINDOW_DAYS = 30;

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal HALF = new BigDecimal("0.50");
    private static final BigDecimal ONE = new BigDecimal("1.00");

    private FestivalWindow() {
    }

    /**
     * 時間窗權重（§FR-17-1 四段不等式）。
     *
     * <pre>
     * window = 0.0   當 d &gt; L + 30       太早，尚未進入備貨期
     *        = 1.0   當 L ≤ d ≤ L + 30   黃金備貨期
     *        = 0.5   當 0 ≤ d &lt; L        來不及正常備貨，但可能有補單機會
     *        = 0.0   當 d &lt; 0            節慶日之後，歸零
     * </pre>
     *
     * @param daysUntilFestival d＝節慶日 − 評估日，正值代表節慶尚未到
     * @param leadTimeDays      L＝品類前置天數，非負
     * @return 0.00 / 0.50 / 1.00，scale 固定為 2
     * @throws IllegalArgumentException 前置天數為負
     */
    public static BigDecimal weight(long daysUntilFestival, int leadTimeDays) {
        if (leadTimeDays < 0) {
            throw new IllegalArgumentException("前置天數不可為負：" + leadTimeDays);
        }
        if (daysUntilFestival < 0) {
            return ZERO; // 已過
        }
        if (daysUntilFestival < leadTimeDays) {
            return HALF; // 補單期
        }
        if (daysUntilFestival <= (long) leadTimeDays + GOLDEN_WINDOW_DAYS) {
            return ONE; // 黃金備貨期
        }
        return ZERO; // 太早
    }
}
