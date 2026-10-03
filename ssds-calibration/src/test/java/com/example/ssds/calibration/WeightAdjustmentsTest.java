package com.example.ssds.calibration;

import static com.example.ssds.calibration.FactorStatisticsTest.sum;
import static com.example.ssds.calibration.FactorStatisticsTest.weights;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.ssds.core.domain.FactorCode;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WeightAdjustmentsTest {

    static final Map<FactorCode, BigDecimal> CURRENT = weights("0.500", "0.100", "0.080", "0.070", "0.150", "0.100");
    static final Map<FactorCode, BigDecimal> SUGGESTED = weights("0.570", "0.080", "0.090", "0.070", "0.110", "0.080");

    @Test
    void acceptingEverythingYieldsTheSuggestion() {
        assertThat(WeightAdjustments.applyAccepted(CURRENT, SUGGESTED, EnumSet.allOf(FactorCode.class)))
                .isEqualTo(SUGGESTED);
    }

    @Test
    void acceptingNothingKeepsCurrent() {
        assertThat(WeightAdjustments.applyAccepted(CURRENT, SUGGESTED, Set.of())).isEqualTo(CURRENT);
    }

    @Test
    void acceptingOneFactorScalesTheRestProportionally() {
        // TREND 0.50 → 0.57；其餘 0.50 縮成 0.43，各乘 0.86
        Map<FactorCode, BigDecimal> result =
                WeightAdjustments.applyAccepted(CURRENT, SUGGESTED, Set.of(FactorCode.TREND));

        assertThat(result.get(FactorCode.TREND)).isEqualByComparingTo("0.570");
        assertThat(result.get(FactorCode.MARGIN)).isEqualByComparingTo("0.086");
        assertThat(result.get(FactorCode.FESTIVAL)).isEqualByComparingTo("0.129");
        assertThat(sum(result)).isEqualByComparingTo("1.000");
    }

    @Test
    void roundingAlwaysSumsToExactlyOne() {
        Map<FactorCode, BigDecimal> thirds = WeightRounding.toThousandths(Map.of(
                FactorCode.TREND, 1.0, FactorCode.MARGIN, 1.0, FactorCode.CVR, 1.0));

        assertThat(sum(thirds)).isEqualByComparingTo("1.000");
        assertThat(thirds.get(FactorCode.TREND)).isEqualByComparingTo("0.334");
        assertThat(thirds.get(FactorCode.CVR)).isEqualByComparingTo("0.333");
    }
}
