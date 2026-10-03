package com.example.ssds.api.scoring.factor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * §5.3.1 平均排名法的母體。{@code peerRawValues} 不包含本品項，計算時會加入 target。
 */
public record PercentileBasis(List<BigDecimal> peerRawValues, boolean imputed, String note) {
    public PercentileBasis {
        Objects.requireNonNull(peerRawValues, "peerRawValues 不可為 null");
        if (peerRawValues.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("百分位母體不得包含 null");
        }
        peerRawValues = List.copyOf(peerRawValues);
    }

    public BigDecimal normalize(BigDecimal target) {
        Objects.requireNonNull(target, "target 不可為 null");
        List<BigDecimal> population = new ArrayList<>(peerRawValues);
        population.add(target);
        long lower = population.stream().filter(value -> value.compareTo(target) < 0).count();
        long equal = population.stream().filter(value -> value.compareTo(target) == 0).count();
        BigDecimal averageRank = BigDecimal.valueOf(lower)
                .add(BigDecimal.ONE)
                .add(BigDecimal.valueOf(equal - 1).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP));
        if (population.size() == 1) return BigDecimal.valueOf(100).setScale(2);
        return averageRank.subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(population.size() - 1L), 2, RoundingMode.HALF_UP);
    }
}
