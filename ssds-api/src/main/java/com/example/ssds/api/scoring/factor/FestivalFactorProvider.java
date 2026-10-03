package com.example.ssds.api.scoring.factor;

import com.example.ssds.core.domain.FactorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/** FR-17-1 FESTIVAL：關聯度乘時間窗，多節慶取最大值。 */
public class FestivalFactorProvider {
    public FactorComputation provide(
            LocalDate evaluationDate,
            int leadTimeDays,
            List<FestivalAffinity> affinities,
            PercentileBasis basis) {
        if (evaluationDate == null || affinities == null || affinities.isEmpty()) {
            return FactorComputation.unavailableBonus(FactorCode.FESTIVAL, "沒有品項節慶關聯資料");
        }
        if (affinities.stream().anyMatch(value -> value.affinity() != null
                && (value.affinity().signum() < 0 || value.affinity().compareTo(BigDecimal.ONE) > 0))) {
            throw new IllegalArgumentException("節慶關聯度必須介於 0 到 1");
        }
        Candidate selected = affinities.stream()
                .filter(value -> value.festivalDate() != null && value.affinity() != null)
                .map(value -> new Candidate(value, value.affinity().multiply(
                        window(ChronoUnit.DAYS.between(evaluationDate, value.festivalDate()), leadTimeDays))))
                .max(Comparator.comparing(Candidate::raw))
                .orElse(null);
        if (selected == null) {
            return FactorComputation.unavailableBonus(FactorCode.FESTIVAL, "節慶日期或關聯度不完整");
        }
        FactorComputation computed = FactorComputation.bonus(
                FactorCode.FESTIVAL,
                selected.raw().setScale(4, RoundingMode.HALF_UP),
                basis,
                false,
                "生效節慶：" + selected.affinity().festivalName());
        return new FactorComputation(
                computed.code(),
                computed.rawValue(),
                computed.normalizedValue(),
                computed.penaltyValue(),
                computed.dataAvailable(),
                computed.imputed(),
                computed.note(),
                null,
                selected.affinity().festivalId());
    }

    static BigDecimal window(long daysUntilFestival, int leadTimeDays) {
        if (leadTimeDays < 0) throw new IllegalArgumentException("leadTimeDays 不得小於 0");
        if (daysUntilFestival > leadTimeDays + 30L) return BigDecimal.ZERO;
        if (daysUntilFestival >= leadTimeDays) return BigDecimal.ONE;
        if (daysUntilFestival >= 0) return new BigDecimal("0.5");
        return BigDecimal.ZERO;
    }

    public record FestivalAffinity(
            Long festivalId,
            String festivalCode,
            String festivalName,
            LocalDate festivalDate,
            BigDecimal affinity) {}

    private record Candidate(FestivalAffinity affinity, BigDecimal raw) {}
}
