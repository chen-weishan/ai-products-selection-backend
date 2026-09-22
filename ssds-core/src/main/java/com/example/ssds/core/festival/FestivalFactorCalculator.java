package com.example.ssds.core.festival;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * FESTIVAL 因子的計算（規格書 §FR-17-1、AC-17-2、AC-17-6）。
 *
 * <pre>
 * 節慶因子(品項, 日期) = max over 所有節慶 f 之 [ affinity(品項, f) × window(日期, f, L) ]
 * </pre>
 *
 * <p>AC-17-2：時間窗權重於每次評分時重算，不是存下來的欄位，因此評估日一律由
 * 呼叫端傳入，本類別不呼叫 {@link LocalDate#now()}——否則這支函式不可測。
 *
 * <p>AC-17-6：取最大值之外還要回報是哪一個節慶生效，供 UI 標示，並寫進
 * {@code score_factor.driving_festival_id}（V30 已建好該欄與 CHECK 約束）。
 */
public final class FestivalFactorCalculator {

    /**
     * 因子原始值的 scale。對應 {@code score_factor.raw_value DECIMAL(12, 4)}。
     *
     * <p>affinity 為 {@code DECIMAL(3, 2)}、window 固定 scale 2，相乘後自然是 scale 4，
     * 此處明寫是為了讓並列比較與測試斷言不受 scale 浮動影響（{@code 0.60} 與
     * {@code 0.6000} 的 {@code equals} 為 false，{@code compareTo} 才為 0）。
     */
    private static final int RAW_VALUE_SCALE = 4;

    private FestivalFactorCalculator() {
    }

    /**
     * 多節慶取最大值（§FR-17-1）。
     *
     * <p>並列時取節慶日較早者：先到的檔期先進入備貨期，標示它對採購比較有行動意義。
     * 規格書沒有規定並列的處理方式，這是設計決定。
     *
     * @param evaluationDate 評估日。由呼叫端傳入，本類別不呼叫 {@code LocalDate.now()}
     * @param leadTimeDays   該品項所屬品類的前置天數，非負
     * @param candidates     該品項的<b>全部</b>節慶關聯，不可事先以日期範圍篩選
     * @return 最大者；{@code candidates} 為空或 null 時回 {@link Optional#empty()}
     *         ＝該因子無資料（§5.7 不扣分、權重分攤），<b>不是</b> 0
     */
    public static Optional<FestivalFactorResult> evaluate(
            LocalDate evaluationDate,
            int leadTimeDays,
            List<FestivalAffinityInput> candidates) {

        if (evaluationDate == null) {
            throw new IllegalArgumentException("評估日不可為 null");
        }
        if (candidates == null || candidates.isEmpty()) {
            // 沒建過關聯＝無資料。與「有關聯但都不在窗內所以算出 0」是兩件事：
            // 後者要回 0 並照常參與加權，前者才走 §5.7 的權重分攤。
            return Optional.empty();
        }

        FestivalFactorResult best = null;
        LocalDate bestFestivalDate = null;

        for (FestivalAffinityInput candidate : candidates) {
            long daysUntilFestival =
                    ChronoUnit.DAYS.between(evaluationDate, candidate.festivalDate());

            BigDecimal window = FestivalWindow.weight(daysUntilFestival, leadTimeDays);
            BigDecimal factorValue = candidate.affinity()
                    .multiply(window)
                    .setScale(RAW_VALUE_SCALE, RoundingMode.HALF_UP);

            if (isBetter(factorValue, candidate.festivalDate(), best, bestFestivalDate)) {
                best = new FestivalFactorResult(
                        factorValue, candidate.festivalId(), candidate.festivalName());
                bestFestivalDate = candidate.festivalDate();
            }
        }

        return Optional.of(best);
    }

    /** 值較大者勝；並列時節慶日較早者勝。 */
    private static boolean isBetter(
            BigDecimal candidateValue,
            LocalDate candidateDate,
            FestivalFactorResult best,
            LocalDate bestDate) {

        if (best == null) {
            return true;
        }
        int valueComparison = candidateValue.compareTo(best.rawValue());
        if (valueComparison != 0) {
            return valueComparison > 0;
        }
        return candidateDate.isBefore(bestDate);
    }
}
