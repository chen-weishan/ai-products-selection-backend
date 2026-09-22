package com.example.ssds.core.climate;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * CLIMATE 因子的適配度計算（規格書 §FR-17-2）。
 *
 * <p>只吃歷史同期氣候統計（{@code climate_normal.avg_temp}）。AC-17-4 明訂短期天氣
 * 預報不得計入分數——預報僅 7–14 天可信，備貨週期常超過三週，時序對不上。
 *
 * <p>降雨機率目前不進本計算，僅供 UI 顯示與開團時機提醒。
 */
public final class ClimateFit {

    /** 輸出的 scale。黃金案例要求 0.717，scale 2 會變成 0.72，對不上規格。 */
    private static final int OUTPUT_SCALE = 3;

    /** 中間運算多留一位，避免先四捨五入再相減造成的累積誤差。 */
    private static final int INTERMEDIATE_SCALE = 4;

    private static final BigDecimal ONE = BigDecimal.ONE.setScale(OUTPUT_SCALE);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(OUTPUT_SCALE);

    private ClimateFit() {
        throw new AssertionError("工具類別，不應被實例化");
    }

    /**
     * 氣候適配度（§FR-17-2）。
     *
     * <pre>
     * Tmin ≤ T ≤ Tmax → fit = 1.0
     * 否則             → fit = max(0, 1 − distance / tolerance)
     *      其中 distance = (T &lt; Tmin) ? (Tmin − T) : (T − Tmax)
     * </pre>
     *
     * <p>distance 是「溫度離適溫區間多遠」，不是區間本身的寬度。
     *
     * @param avgTemp 該月該區域的歷史月均溫（{@code climate_normal.avg_temp}）
     * @param range   品項或品類的適溫區間與容忍範圍
     * @return 0–1，scale 固定 3。回傳 0 代表「差很遠」，與「無資料」是兩件事——
     *         無資料的情況不會走到這支，呼叫端回 {@code Optional.empty()}（§5.7）
     */
    public static BigDecimal fit(BigDecimal avgTemp, TemperatureRange range) {
        if (avgTemp == null || range == null) {
            throw new IllegalArgumentException("月均溫與適溫區間皆不可為 null");
        }

        if (range.min().compareTo(avgTemp) <= 0 && range.max().compareTo(avgTemp) >= 0) {
            return ONE;
        }

        BigDecimal distance = avgTemp.compareTo(range.min()) < 0
                ? range.min().subtract(avgTemp)   // 太冷
                : avgTemp.subtract(range.max());  // 太熱

        BigDecimal decay = distance.divide(
                range.tolerance(), INTERMEDIATE_SCALE, RoundingMode.HALF_UP);

        return BigDecimal.ONE.subtract(decay)
                .max(BigDecimal.ZERO)
                .setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);
    }
}
