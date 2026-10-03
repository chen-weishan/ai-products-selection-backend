package com.example.ssds.api.scoring.factor;

import com.example.ssds.api.scoring.ScoreEvaluationService.FactorInput;
import com.example.ssds.core.domain.FactorCode;
import java.math.BigDecimal;
import java.util.Objects;

/** 單一因子的確定性計算結果；可直接轉成正式評分服務的輸入。 */
public record FactorComputation(
        FactorCode code,
        BigDecimal rawValue,
        BigDecimal normalizedValue,
        BigDecimal penaltyValue,
        boolean dataAvailable,
        boolean imputed,
        String note,
        Long drivingKeywordId,
        Long drivingFestivalId) {

    public FactorComputation {
        Objects.requireNonNull(code, "code 不可為 null");
    }

    public FactorInput toEvaluationInput() {
        return new FactorInput(
                rawValue,
                normalizedValue,
                penaltyValue,
                dataAvailable,
                imputed,
                note,
                drivingKeywordId,
                drivingFestivalId);
    }

    static FactorComputation bonus(
            FactorCode code, BigDecimal raw, PercentileBasis basis, boolean imputed, String note) {
        if (raw == null || basis == null) {
            return unavailableBonus(code, note == null ? "缺少原始值或百分位母體" : note);
        }
        return new FactorComputation(
                code,
                raw,
                basis.normalize(raw),
                null,
                true,
                imputed || basis.imputed(),
                mergeNotes(note, basis.note()),
                null,
                null);
    }

    static FactorComputation unavailableBonus(FactorCode code, String note) {
        return new FactorComputation(code, null, null, null, false, false, note, null, null);
    }

    static FactorComputation penalty(
            FactorCode code, BigDecimal raw, BigDecimal penalty, boolean available, String note) {
        return new FactorComputation(code, raw, null, penalty, available, false, note, null, null);
    }

    private static String mergeNotes(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return first + "；" + second;
    }
}
