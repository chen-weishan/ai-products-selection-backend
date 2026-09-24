package com.example.ssds.api.scoring.factor;

import com.example.ssds.core.domain.FactorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/** §5.3.3 TREND：各關鍵字雙窗口斜率取最高者，量級不足時刻意計為 0。 */
public class TrendFactorProvider {
    private static final BigDecimal SEVEN_DAY_WEIGHT = new BigDecimal("0.7");
    private static final BigDecimal THIRTY_DAY_WEIGHT = new BigDecimal("0.3");

    public FactorComputation provide(List<KeywordTrend> keywordTrends, PercentileBasis basis) {
        if (keywordTrends == null || keywordTrends.isEmpty()) {
            return FactorComputation.unavailableBonus(FactorCode.TREND, "未關聯關鍵字或熱度歷史不足 7 日");
        }
        Candidate selected = keywordTrends.stream()
                .filter(value -> value.slope7d() != null && value.slope30d() != null)
                .map(value -> new Candidate(value, raw(value)))
                .max(Comparator.comparing(Candidate::raw))
                .orElse(null);
        if (selected == null) {
            return FactorComputation.unavailableBonus(FactorCode.TREND, "所有關鍵字的熱度歷史皆不足");
        }
        String note = "生效關鍵字：" + selected.trend().keyword();
        if (selected.trend().volumeBelowFloor()) {
            return new FactorComputation(
                    FactorCode.TREND,
                    selected.raw(),
                    BigDecimal.ZERO.setScale(2),
                    null,
                    true,
                    false,
                    note + "；熱度量級不足，百分位依規格歸零",
                    selected.trend().keywordId(),
                    null);
        }
        FactorComputation computed = FactorComputation.bonus(
                FactorCode.TREND, selected.raw(), basis, false, note);
        return new FactorComputation(
                computed.code(),
                computed.rawValue(),
                computed.normalizedValue(),
                computed.penaltyValue(),
                computed.dataAvailable(),
                computed.imputed(),
                computed.note(),
                selected.trend().keywordId(),
                null);
    }

    private static BigDecimal raw(KeywordTrend trend) {
        return trend.slope7d().multiply(SEVEN_DAY_WEIGHT)
                .add(trend.slope30d().multiply(THIRTY_DAY_WEIGHT))
                .setScale(4, RoundingMode.HALF_UP);
    }

    public record KeywordTrend(
            Long keywordId,
            String keyword,
            BigDecimal slope7d,
            BigDecimal slope30d,
            boolean volumeBelowFloor) {}

    private record Candidate(KeywordTrend trend, BigDecimal raw) {}
}
