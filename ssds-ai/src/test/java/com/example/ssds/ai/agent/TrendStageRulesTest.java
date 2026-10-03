package com.example.ssds.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssds.ai.model.trend.*;
import com.example.ssds.core.domain.HeatStage;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrendStageRulesTest {
    @Test
    void followsV301ThirtyDaySlopeBoundaries() {
        assertEquals(HeatStage.DECLINING, evaluate("-0.11"));
        assertEquals(HeatStage.PLATEAU, evaluate("-0.10"));
        assertEquals(HeatStage.PLATEAU, evaluate("0"));
        assertEquals(HeatStage.PLATEAU, evaluate("0.10"));
        assertEquals(HeatStage.RISING, evaluate("0.11"));
    }

    @Test
    void risingDoesNotRequireThreeWeeksOfHistory() {
        assertEquals(HeatStage.RISING, evaluate("0.25"));
    }

    @Test
    void missingThirtyDaySlopeFallsBackToPlateau() {
        assertEquals(HeatStage.PLATEAU, evaluate(null));
    }

    private static HeatStage evaluate(String slope30d) {
        TrendInterpreterInput input = new TrendInterpreterInput(
                1L,
                List.of(new TrendInterpreterInput.CompositePoint(
                        "2026-09-22", new BigDecimal("50"), new BigDecimal("9.99"),
                        slope30d == null ? null : new BigDecimal(slope30d))),
                List.of(),
                List.of(
                        new TrendInterpreterInput.AllowedOutput(HeatStage.RISING, 1, 56),
                        new TrendInterpreterInput.AllowedOutput(HeatStage.PLATEAU, 1, 42),
                        new TrendInterpreterInput.AllowedOutput(HeatStage.DECLINING, 1, 17)));
        return TrendStageRules.evaluate(input).stage();
    }
}
