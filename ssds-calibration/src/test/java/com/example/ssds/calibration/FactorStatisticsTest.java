package com.example.ssds.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FactorStatisticsTest {

    static final Map<FactorCode, BigDecimal> VIRAL = weights("0.500", "0.100", "0.080", "0.070", "0.150", "0.100");
    static final Map<FactorCode, BigDecimal> REPLENISHMENT = weights("0.100", "0.300", "0.300", "0.120", "0.080", "0.100");
    static final Map<SceneType, Map<FactorCode, BigDecimal>> CURRENT = Map.of(
            SceneType.VIRAL, VIRAL, SceneType.REPLENISHMENT, REPLENISHMENT);

    @Test
    void noSamplesKeepsCurrentWeights() {
        FactorStatistics.Result result = FactorStatistics.analyze(List.of(), CURRENT, 200, 10);

        assertThat(result.shrinkage()).isZero();
        assertThat(result.suggestedWeights()).isEqualTo(CURRENT);
        assertThat(result.factors()).allSatisfy(f -> assertThat(f.sufficient()).isFalse());
    }

    @Test
    void predictiveFactorGainsWeightAndEveryScenesSumsToOne() {
        // TREND 與銷量完全同序；MARGIN 完全反序；其餘因子無資料
        List<CalibrationSample> samples = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            samples.add(new CalibrationSample(i, SceneType.VIRAL,
                    Map.of(FactorCode.TREND, (double) i, FactorCode.MARGIN, (double) -i), 0, i * 10, false));
        }

        FactorStatistics.Result result = FactorStatistics.analyze(samples, CURRENT, 200, 10);

        assertThat(result.shrinkage()).isEqualTo(1.0);
        Map<FactorCode, BigDecimal> viral = result.suggestedWeights().get(SceneType.VIRAL);
        assertThat(viral.get(FactorCode.TREND)).isGreaterThan(VIRAL.get(FactorCode.TREND));
        assertThat(viral.get(FactorCode.MARGIN)).isZero();
        for (Map<FactorCode, BigDecimal> scene : result.suggestedWeights().values()) {
            assertThat(sum(scene)).isEqualByComparingTo("1.000");
        }
        assertThat(result.factors()).filteredOn(f -> f.code() == FactorCode.FESTIVAL)
                .singleElement().satisfies(f -> {
                    assertThat(f.n()).isZero();
                    assertThat(f.sufficient()).isFalse();
                    assertThat(f.correlation()).isNull();
                });
    }

    @Test
    void smallSampleShrinksTheAdjustment() {
        List<CalibrationSample> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            samples.add(new CalibrationSample(i, SceneType.VIRAL,
                    Map.of(FactorCode.TREND, (double) i, FactorCode.MARGIN, (double) -i), 0, i, false));
        }

        FactorStatistics.Result small = FactorStatistics.analyze(samples, CURRENT, 200, 10);
        FactorStatistics.Result full = FactorStatistics.analyze(samples, CURRENT, 20, 10);

        assertThat(small.shrinkage()).isEqualTo(0.1);
        BigDecimal smallTrend = small.suggestedWeights().get(SceneType.VIRAL).get(FactorCode.TREND);
        BigDecimal fullTrend = full.suggestedWeights().get(SceneType.VIRAL).get(FactorCode.TREND);
        assertThat(smallTrend).isGreaterThan(VIRAL.get(FactorCode.TREND)).isLessThan(fullTrend);
    }

    @Test
    void factorBelowMinFactorSampleIsNotAdjusted() {
        List<CalibrationSample> samples = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            samples.add(new CalibrationSample(i, SceneType.VIRAL, Map.of(FactorCode.TREND, (double) i), 0, i, false));
        }

        FactorStatistics.Result result = FactorStatistics.analyze(samples, CURRENT, 9, 10);

        assertThat(result.factors().get(0).correlation()).isNotNull();
        assertThat(result.factors().get(0).sufficient()).isFalse();
        assertThat(result.suggestedWeights()).isEqualTo(CURRENT);
    }

    static Map<FactorCode, BigDecimal> weights(String... values) {
        Map<FactorCode, BigDecimal> map = new EnumMap<>(FactorCode.class);
        for (int i = 0; i < values.length; i++) {
            map.put(FactorStatistics.BONUS_FACTORS.get(i), new BigDecimal(values[i]));
        }
        return map;
    }

    static BigDecimal sum(Map<FactorCode, BigDecimal> weights) {
        return weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
