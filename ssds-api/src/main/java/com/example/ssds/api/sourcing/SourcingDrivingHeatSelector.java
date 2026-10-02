package com.example.ssds.api.sourcing;

import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.TrendKeyword;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;

/** §5.3.3：只從啟用關鍵字的最新合格合成列選出最高 trend_raw。 */
public final class SourcingDrivingHeatSelector {
    private static final BigDecimal SLOPE_7D_WEIGHT = new BigDecimal("0.7");
    private static final BigDecimal SLOPE_30D_WEIGHT = new BigDecimal("0.3");

    private SourcingDrivingHeatSelector() {
    }

    public static HeatCompositeDaily select(
            Collection<TrendKeyword> keywords,
            Map<Long, HeatCompositeDaily> latestByKeyword) {
        LocalDate freshestDate = keywords.stream()
                .filter(TrendKeyword::isEnabled)
                .map(keyword -> latestByKeyword.get(keyword.getId()))
                .filter(SourcingDrivingHeatSelector::isEligible)
                .map(HeatCompositeDaily::getStatDate)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (freshestDate == null) {
            return null;
        }

        return keywords.stream()
                .filter(TrendKeyword::isEnabled)
                .map(keyword -> latestByKeyword.get(keyword.getId()))
                .filter(SourcingDrivingHeatSelector::isEligible)
                .filter(value -> freshestDate.equals(value.getStatDate()))
                .max(Comparator
                        .comparing(SourcingDrivingHeatSelector::trendRaw)
                        .thenComparing(
                                value -> value.getKeyword().getId(),
                                Comparator.reverseOrder()))
                .orElse(null);
    }

    private static boolean isEligible(HeatCompositeDaily value) {
        return value != null
                && value.getKeyword() != null
                && value.getKeyword().isEnabled()
                && value.getStatDate() != null
                && value.getStage() != null
                && value.getSlope7d() != null
                && value.getEstimatedLifespanDays() != null;
    }

    private static BigDecimal trendRaw(HeatCompositeDaily value) {
        return value.getSlope7d().multiply(SLOPE_7D_WEIGHT)
                .add((value.getSlope30d() == null ? BigDecimal.ZERO : value.getSlope30d())
                        .multiply(SLOPE_30D_WEIGHT));
    }
}
