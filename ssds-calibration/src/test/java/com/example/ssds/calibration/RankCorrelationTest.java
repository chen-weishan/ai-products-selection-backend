package com.example.ssds.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class RankCorrelationTest {

    @Test
    void monotoneButNonLinearRelationIsPerfectRankCorrelation() {
        RankCorrelation.Result result = RankCorrelation.of(
                new double[] {1, 2, 3, 4, 5}, new double[] {1, 10, 100, 1000, 10000});

        assertThat(result.correlation()).isCloseTo(1.0, within(1e-12));
        assertThat(result.pValue()).isZero();
        assertThat(result.n()).isEqualTo(5);
    }

    @Test
    void undefinedForTooFewPairsOrNoVariance() {
        assertThat(RankCorrelation.of(new double[] {1, 2}, new double[] {3, 4})).isNull();
        assertThat(RankCorrelation.of(new double[] {5, 5, 5}, new double[] {1, 2, 3})).isNull();
    }

    @Test
    void pValueMatchesStudentT() {
        // r = 0.5, n = 12 → t = 0.5·√(10/0.75) ≈ 1.8257，df 10 雙尾 p ≈ 0.0979
        assertThat(RankCorrelation.pValue(0.5, 12)).isCloseTo(0.0979, within(1e-3));
    }
}
