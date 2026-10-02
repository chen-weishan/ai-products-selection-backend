package com.example.ssds.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 人工熱度標記換算規則（規格書 §FR-14-1-a，v3.0 補齊）。
 *
 * <p><b>刻意保持純 Java、不依賴 JPA／資料庫</b>（比照 {@link HeatTrendCalculator} 的既定慣例，
 * ssds-core 模組的計算邏輯要能不開資料庫就單元測試）。呼叫端（ssds-api 的每日排程）
 * 負責從 {@code manual_heat_tag} 撈出某標的當下未失效的標記，組成 {@link Observation}
 * 清單後交給本類別計算。
 *
 * <p><b>步驟 5（稀疏期向前填補）刻意不在本類別實作</b>：只要呼叫端每天都用「當天」
 * 當作 evaluationDate 重新呼叫一次 {@link #computeRawValue}，衰減會隨 age 自然遞減，
 * 「向前填補」就是這個每日重算的副作用，不需要額外的回填程式碼。當某標的所有標記
 * 都已失效（age ≥ expireDays）時，{@link #computeRawValue} 回傳 {@link Optional#empty()}，
 * 呼叫端應解讀為「衰減已歸零，當天不再寫入 heat_reading」。
 */
public final class ManualHeatTagCalculator {

    private ManualHeatTagCalculator() {}

    /** §FR-14-1-a 步驟 2 預設值：對應環境變數 HEAT_TAG_HALVE_AFTER_DAYS。 */
    public static final int DEFAULT_HALVE_AFTER_DAYS = 14;

    /** §FR-14-1-a 步驟 2 預設值：對應環境變數 HEAT_TAG_EXPIRE_DAYS。 */
    public static final int DEFAULT_EXPIRE_DAYS = 30;

    /** §FR-14-1-a 步驟 4 信心係數：單一標記人。 */
    private static final BigDecimal CONFIDENCE_SINGLE = new BigDecimal("0.6");

    /** §FR-14-1-a 步驟 4 信心係數：兩位標記人。 */
    private static final BigDecimal CONFIDENCE_DOUBLE = new BigDecimal("0.8");

    /** §FR-14-1-a 步驟 4 信心係數：三位（含）以上標記人。 */
    private static final BigDecimal CONFIDENCE_FULL = BigDecimal.ONE;

    private static final int SCALE = 4;

    /**
     * 單筆標記（{@code manual_heat_tag} 一列）在計算時需要的最小資料。
     *
     * @param heatLevel  1–5 熱度等級
     * @param observedAt 觀察時間
     * @param taggerId   標記人 id（用於步驟 4 相異人數計算，同一人多次標記只計一次）
     */
    public record Observation(short heatLevel, Instant observedAt, Long taggerId) {
        public Observation {
            if (heatLevel < 1 || heatLevel > 5) {
                throw new IllegalArgumentException("熱度等級必須介於 1–5，實際為 " + heatLevel);
            }
        }
    }

    /**
     * §FR-14-1-a 步驟 1：單筆標記轉為基礎值。
     * 1→20、2→40、3→60、4→80、5→100，等距換算，無需查表以外的邏輯。
     */
    public static BigDecimal baseValue(short heatLevel) {
        return BigDecimal.valueOf(heatLevel * 20L);
    }

    /**
     * §FR-14-1-a 步驟 2：階梯式時間衰減（非指數半衰）。
     *
     * <pre>
     * decay(age) = 1.0   當  age &lt; halveAfterDays
     *            = 0.5   當  halveAfterDays ≤ age &lt; expireDays
     *            = 0.0   當  age ≥ expireDays
     * </pre>
     *
     * @param ageDays       觀察日至評估日的天數
     * @param halveAfterDays 降為 0.5 的天數門檻（預設 14，見 {@link #DEFAULT_HALVE_AFTER_DAYS}）
     * @param expireDays     完全失效的天數門檻（預設 30，見 {@link #DEFAULT_EXPIRE_DAYS}）
     */
    public static BigDecimal decayFactor(long ageDays, int halveAfterDays, int expireDays) {
        if (ageDays >= expireDays) {
            return BigDecimal.ZERO;
        }
        return ageDays >= halveAfterDays ? new BigDecimal("0.5") : BigDecimal.ONE;
    }

    /**
     * §FR-14-1-a 步驟 4：標記人數信心係數。
     * 相異標記人數看的是「人」不是「筆」——同一人連貼五則不會比較可信。
     *
     * @param distinctTaggers 未失效標記的相異標記人數
     */
    public static BigDecimal confidenceFactor(long distinctTaggers) {
        if (distinctTaggers <= 1) {
            return CONFIDENCE_SINGLE;
        }
        if (distinctTaggers == 2) {
            return CONFIDENCE_DOUBLE;
        }
        return CONFIDENCE_FULL;
    }

    /**
     * §FR-14-1-a 步驟 1–4 一次算完：由「某標的、某評估日的全部觀測」直接算出
     * 當天應寫入 {@code heat_reading.raw_value}（MANUAL 來源）的值。
     *
     * <p>內部流程：
     * <ol>
     *   <li>逐筆換算基礎值（步驟 1）與該筆在 evaluationDate 的衰減係數（步驟 2）</li>
     *   <li>已完全失效（decay = 0）的標記排除在外——不參與加權平均，也不計入相異人數</li>
     *   <li>剩餘標記以衰減係數加權平均（步驟 3）</li>
     *   <li>乘上相異標記人數的信心係數（步驟 4）</li>
     * </ol>
     *
     * @param observations   該標的全部標記（含已失效者亦可傳入，本方法會自行過濾）
     * @param evaluationDate 評估時間點（通常為排程執行當下的 Instant，用於算 age）
     * @param halveAfterDays 見 {@link #decayFactor}
     * @param expireDays     見 {@link #decayFactor}
     * @return 當天的 raw_value；該標的所有標記皆已失效（或清單為空）時回傳空值，
     *         呼叫端應解讀為「當天不再寫入 heat_reading」（步驟 5 的向前填補終點）
     */
    public static Optional<BigDecimal> computeRawValue(
            List<Observation> observations,
            Instant evaluationDate,
            int halveAfterDays,
            int expireDays) {

        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        Set<Long> distinctTaggers = new java.util.LinkedHashSet<>();

        for (Observation obs : observations) {
            long ageDays = ChronoUnit.DAYS.between(obs.observedAt(), evaluationDate);
            if (ageDays < 0) {
                // 觀察時間晚於評估時間，理論上不該發生（排程只會用「現在」當評估點），
                // 視同尚未衰減，age 收斂為 0，避免產生負的 age 破壞衰減公式。
                ageDays = 0;
            }
            BigDecimal decay = decayFactor(ageDays, halveAfterDays, expireDays);
            if (decay.signum() == 0) {
                continue; // 已失效，不參與聚合，也不計入信心係數的人數
            }
            BigDecimal base = baseValue(obs.heatLevel());
            weightedSum = weightedSum.add(base.multiply(decay));
            weightSum = weightSum.add(decay);
            distinctTaggers.add(obs.taggerId());
        }

        if (weightSum.signum() == 0) {
            return Optional.empty();
        }

        BigDecimal aggregated = weightedSum.divide(weightSum, SCALE, RoundingMode.HALF_UP);
        BigDecimal confidence = confidenceFactor(distinctTaggers.size());
        BigDecimal rawValue = aggregated.multiply(confidence).setScale(3, RoundingMode.HALF_UP);
        return Optional.of(rawValue);
    }
}
