package com.example.ssds.core.scoring;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Grade;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScoringEngineTest {
    private final ScoringEngine engine = new ScoringEngine();

    @Test
    void matchesSpecificationGoldenExample() {
        Map<FactorCode, ScoringEngine.FactorValue> factors = factors(
                "96", "88", "90", "82", "85", "44", "0", "4", "0");
        ScoringEngine.Result result = engine.calculate(
                factors,
                weights("0.50", "0.10", "0.08", "0.07", "0.15", "0.10"),
                new BigDecimal("85"),
                new BigDecimal("70"));

        assertEquals(new BigDecimal("86.89"), result.bonusSubtotal());
        assertEquals(new BigDecimal("4.00"), result.penaltySubtotal());
        assertEquals(new BigDecimal("82.89"), result.finalScore());
        assertEquals(Grade.B, result.grade());
    }

    @Test
    void redistributesUnavailableFactorWeight() {
        Map<FactorCode, ScoringEngine.FactorValue> factors = factors(
                "96", "88", "90", "82", "85", null, "0", "0", "0");
        ScoringEngine.Result result = engine.calculate(
                factors,
                weights("0.50", "0.10", "0.08", "0.07", "0.15", "0.10"),
                new BigDecimal("85"),
                new BigDecimal("70"));

        BigDecimal sum = result.effectiveWeights().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(BigDecimal.ONE));
        assertEquals(0, result.effectiveWeights().get(FactorCode.CLIMATE).compareTo(BigDecimal.ZERO));
    }

    @Test
    void suppressesGradeAWhenPenaltyReachesTwenty() {
        ScoringEngine.Result result = engine.calculate(
                factors("100", "100", "100", "100", "100", "100", "20", "0", "0"),
                weights("0.50", "0.10", "0.08", "0.07", "0.15", "0.10"),
                new BigDecimal("80"),
                new BigDecimal("65"));

        assertEquals(new BigDecimal("80.00"), result.finalScore());
        assertEquals(Grade.B, result.grade());
    }

    private static Map<FactorCode, ScoringEngine.FactorValue> factors(
            String trend,
            String margin,
            String cvr,
            String priceFit,
            String festival,
            String climate,
            String review,
            String logistics,
            String inventory) {
        EnumMap<FactorCode, ScoringEngine.FactorValue> values = new EnumMap<>(FactorCode.class);
        bonus(values, FactorCode.TREND, trend);
        bonus(values, FactorCode.MARGIN, margin);
        bonus(values, FactorCode.CVR, cvr);
        bonus(values, FactorCode.PRICE_FIT, priceFit);
        bonus(values, FactorCode.FESTIVAL, festival);
        bonus(values, FactorCode.CLIMATE, climate);
        penalty(values, FactorCode.REVIEW_RISK, review);
        penalty(values, FactorCode.LOGISTICS_RISK, logistics);
        penalty(values, FactorCode.INVENTORY_RISK, inventory);
        return values;
    }

    private static void bonus(
            Map<FactorCode, ScoringEngine.FactorValue> values, FactorCode code, String value) {
        values.put(code, new ScoringEngine.FactorValue(
                value == null ? null : new BigDecimal(value), null, value != null));
    }

    private static void penalty(
            Map<FactorCode, ScoringEngine.FactorValue> values, FactorCode code, String value) {
        values.put(code, new ScoringEngine.FactorValue(
                null, value == null ? null : new BigDecimal(value), value != null));
    }

    private static Map<FactorCode, BigDecimal> weights(
            String trend, String margin, String cvr, String priceFit, String festival, String climate) {
        EnumMap<FactorCode, BigDecimal> weights = new EnumMap<>(FactorCode.class);
        weights.put(FactorCode.TREND, new BigDecimal(trend));
        weights.put(FactorCode.MARGIN, new BigDecimal(margin));
        weights.put(FactorCode.CVR, new BigDecimal(cvr));
        weights.put(FactorCode.PRICE_FIT, new BigDecimal(priceFit));
        weights.put(FactorCode.FESTIVAL, new BigDecimal(festival));
        weights.put(FactorCode.CLIMATE, new BigDecimal(climate));
        return weights;
    }
}
