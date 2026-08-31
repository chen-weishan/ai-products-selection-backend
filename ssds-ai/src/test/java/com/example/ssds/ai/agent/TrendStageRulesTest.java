package com.example.ssds.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssds.ai.model.*;
import com.example.ssds.ai.schema.TrendInterpreterResponseParserTest;
import com.example.ssds.core.domain.HeatStage;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrendStageRulesTest {
    @Test
    void detectsThreeGrowingWeeksAsRising() {
        assertEquals(HeatStage.RISING,
                TrendStageRules.evaluate(TrendInterpreterResponseParserTest.input()).stage());
    }

    @Test
    void negativeThirtyDaySlopeBelowThresholdIsDeclining() {
        TrendInterpreterInput original = TrendInterpreterResponseParserTest.input();
        var points = original.compositeSeries().stream().map(point ->
                point == original.compositeSeries().getLast()
                        ? new TrendInterpreterInput.CompositePoint(
                                point.date(), point.compositeValue(), point.slope7d(),
                                new BigDecimal("-0.11"))
                        : point).toList();
        TrendInterpreterInput input = new TrendInterpreterInput(
                original.keywordId(), points, original.sourceTrends(), original.allowedOutputs());

        assertEquals(HeatStage.DECLINING, TrendStageRules.evaluate(input).stage());
    }

    @Test
    void insufficientGrowthEvidenceFallsBackToPlateau() {
        TrendInterpreterInput original = TrendInterpreterResponseParserTest.input();
        TrendInterpreterInput input = new TrendInterpreterInput(
                original.keywordId(), List.of(original.compositeSeries().getLast()),
                original.sourceTrends(), original.allowedOutputs());

        assertEquals(HeatStage.PLATEAU, TrendStageRules.evaluate(input).stage());
    }
}
