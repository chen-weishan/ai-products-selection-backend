package com.example.ssds.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class HeatTrendCalculatorTest {

    @Test
    void slopeUsesOneAsMinimumDenominator() {
        assertEquals(new BigDecimal("0.5000"),
                HeatTrendCalculator.slope(new BigDecimal("1.0"), new BigDecimal("0.5")));
    }

    @Test
    void stageUsesSlope30dAndStrictTenPercentBoundaries() {
        assertEquals(HeatStage.RISING, HeatTrendCalculator.determineStage(new BigDecimal("0.1001")));
        assertEquals(HeatStage.PLATEAU, HeatTrendCalculator.determineStage(new BigDecimal("0.1000")));
        assertEquals(HeatStage.PLATEAU, HeatTrendCalculator.determineStage(new BigDecimal("-0.1000")));
        assertEquals(HeatStage.DECLINING, HeatTrendCalculator.determineStage(new BigDecimal("-0.1001")));
        assertEquals(HeatStage.PLATEAU, HeatTrendCalculator.determineStage(null));
    }

    @Test
    void stageWeeksAreCeilingOfContinuousDaysDividedBySeven() {
        assertEquals((short) 1, HeatTrendCalculator.stageWeeksFromContinuousDays(1));
        assertEquals((short) 1, HeatTrendCalculator.stageWeeksFromContinuousDays(7));
        assertEquals((short) 2, HeatTrendCalculator.stageWeeksFromContinuousDays(8));
        assertEquals((short) 3, HeatTrendCalculator.stageWeeksFromContinuousDays(15));
    }

    @Test
    void divergenceOnlyFlagsShortTermNegativeWhileLongTermPositive() {
        assertTrue(HeatTrendCalculator.detectDivergence(
                new BigDecimal("-0.01"), new BigDecimal("0.01")));
        assertFalse(HeatTrendCalculator.detectDivergence(
                new BigDecimal("0.01"), new BigDecimal("-0.01")));
        assertFalse(HeatTrendCalculator.detectDivergence(
                new BigDecimal("-0.01"), new BigDecimal("-0.01")));
    }
}
