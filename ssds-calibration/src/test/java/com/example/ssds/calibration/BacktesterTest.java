package com.example.ssds.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BacktesterTest {

    /** §5.5 黃金案例：日式抹茶夾心餅乾，VIRAL，權重 v3。 */
    static final Map<FactorCode, Double> GOLDEN_WEIGHTS = Map.of(
            FactorCode.TREND, 0.50, FactorCode.MARGIN, 0.10, FactorCode.CVR, 0.08,
            FactorCode.PRICE_FIT, 0.07, FactorCode.FESTIVAL, 0.15, FactorCode.CLIMATE, 0.10);
    static final Map<FactorCode, Double> GOLDEN_VALUES = Map.of(
            FactorCode.TREND, 96.0, FactorCode.MARGIN, 88.0, FactorCode.CVR, 90.0,
            FactorCode.PRICE_FIT, 82.0, FactorCode.FESTIVAL, 85.0, FactorCode.CLIMATE, 44.0);
    static final WeightScheme.GradeCut VIRAL_CUT = new WeightScheme.GradeCut(85, 70);

    @Test
    void goldenCaseReproducesSpecScoreAndGrade() {
        CalibrationSample sample = new CalibrationSample(1, SceneType.VIRAL, GOLDEN_VALUES, 4.0, 100, true);

        double score = Backtester.finalScore(sample, GOLDEN_WEIGHTS);

        assertThat(score).isEqualTo(82.89);
        assertThat(Backtester.grade(score, 4.0, VIRAL_CUT)).isEqualTo(Grade.B);
    }

    @Test
    void novemberVariantOfGoldenCaseIsGradeA() {
        Map<FactorCode, Double> november = new EnumMap<>(GOLDEN_VALUES);
        november.put(FactorCode.CLIMATE, 82.0);
        CalibrationSample sample = new CalibrationSample(1, SceneType.VIRAL, november, 0, 100, true);

        double score = Backtester.finalScore(sample, GOLDEN_WEIGHTS);

        assertThat(score).isEqualTo(90.69);
        assertThat(Backtester.grade(score, 0, VIRAL_CUT)).isEqualTo(Grade.A);
    }

    @Test
    void penaltyOfTwentyOrMoreCapsGradeAtB() {
        assertThat(Backtester.grade(95, 20, VIRAL_CUT)).isEqualTo(Grade.B);
        assertThat(Backtester.grade(95, 19.9, VIRAL_CUT)).isEqualTo(Grade.A);
    }

    @Test
    void missingFactorWeightIsRedistributedProportionally() {
        // 只有 TREND 與 MARGIN 有值：(0.5·90 + 0.1·60) / 0.6 = 85
        CalibrationSample sample = new CalibrationSample(1, SceneType.VIRAL,
                Map.of(FactorCode.TREND, 90.0, FactorCode.MARGIN, 60.0), 0, 10, false);

        assertThat(Backtester.finalScore(sample, GOLDEN_WEIGHTS)).isEqualTo(85.0);
    }

    @Test
    void runReportsCorrelationAndGradeAHitRate() {
        Map<SceneType, Map<FactorCode, Double>> weights = Map.of(SceneType.VIRAL, GOLDEN_WEIGHTS);
        Map<SceneType, WeightScheme.GradeCut> cuts = Map.of(SceneType.VIRAL, VIRAL_CUT);
        WeightScheme scheme = new WeightScheme("VERSION", "v3", 3L, weights, cuts);
        List<CalibrationSample> samples = List.of(
                uniform(1, 95, 900, true),
                uniform(2, 90, 700, false),
                uniform(3, 75, 300, true),
                uniform(4, 50, 100, false));

        Backtester.Outcome outcome = Backtester.run(samples, scheme);

        assertThat(outcome.sampleSize()).isEqualTo(4);
        assertThat(outcome.correlation()).isCloseTo(1.0, within(1e-9));
        // 手算：cov 21000、varX 1225、varY 400000 → 21000 / √(1225·400000) = 3/√10；排序完全一致但非線性，故 < Spearman
        assertThat(outcome.pearson()).isCloseTo(3 / Math.sqrt(10), within(1e-9));
        assertThat(outcome.gradeACount()).isEqualTo(2);
        assertThat(outcome.gradeAHitCount()).isEqualTo(1);
        assertThat(outcome.gradeAHitRate()).isEqualTo(0.5);
        assertThat(outcome.versionId()).isEqualTo(3L);
    }

    @Test
    void noGradeAMeansHitRateIsUndefined() {
        WeightScheme scheme = new WeightScheme("VERSION", "v3", 3L,
                Map.of(SceneType.VIRAL, GOLDEN_WEIGHTS), Map.of(SceneType.VIRAL, VIRAL_CUT));

        Backtester.Outcome outcome = Backtester.run(List.of(uniform(1, 10, 5, true)), scheme);

        assertThat(outcome.gradeAHitRate()).isNull();
        assertThat(outcome.correlation()).isNull();
        assertThat(outcome.pearson()).isNull();
    }

    private static CalibrationSample uniform(long id, double value, double qty, boolean hit) {
        Map<FactorCode, Double> values = new EnumMap<>(FactorCode.class);
        GOLDEN_WEIGHTS.keySet().forEach(code -> values.put(code, value));
        return new CalibrationSample(id, SceneType.VIRAL, values, 0, qty, hit);
    }
}
