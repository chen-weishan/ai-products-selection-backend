package com.example.ssds.ai.schema;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.model.*;
import com.example.ssds.core.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TrendInterpreterResponseParserTest {
    private final TrendInterpreterResponseParser parser =
            new TrendInterpreterResponseParser(new ObjectMapper());

    @Test
    void acceptsCleanJsonMatchingOneAllowedTuple() {
        TrendInterpreterOutput output = parser.parse(validJson(), input());
        assertEquals(HeatStage.RISING, output.stage());
        assertEquals(4, output.stageWeeks());
        assertEquals(56, output.estimatedLifespanDays());
    }

    @Test
    void rejectsMarkdownTrailingTextAndMixedCandidateNumbers() {
        assertThrows(AiSchemaValidationException.class,
                () -> parser.parse("```json\n" + validJson() + "\n```", input()));
        assertThrows(AiSchemaValidationException.class,
                () -> parser.parse(validJson() + "完成", input()));
        assertThrows(AiSchemaValidationException.class,
                () -> parser.parse(validJson().replace("56", "42"), input()));
    }

    public static TrendInterpreterInput input() {
        LocalDate latest = LocalDate.of(2026, 8, 26);
        return new TrendInterpreterInput(
                31L,
                List.of(
                        point(latest.minusDays(21), "40", "0.10"),
                        point(latest.minusDays(14), "50", "0.15"),
                        point(latest.minusDays(7), "60", "0.20"),
                        point(latest, "70", "0.30")),
                List.of(new TrendInterpreterInput.SourceTrend(
                        HeatSourceCode.THREADS,
                        new BigDecimal("0.20"),
                        new BigDecimal("0.30"),
                        SourceAvailability.AVAILABLE)),
                List.of(
                        new TrendInterpreterInput.AllowedOutput(HeatStage.RISING, 4, 56),
                        new TrendInterpreterInput.AllowedOutput(HeatStage.PLATEAU, 1, 42),
                        new TrendInterpreterInput.AllowedOutput(HeatStage.DECLINING, 1, 17)));
    }

    public static String validJson() {
        return "{\"stage\":\"RISING\",\"stageWeeks\":4,\"estimatedLifespanDays\":56}";
    }

    private static TrendInterpreterInput.CompositePoint point(
            LocalDate date, String value, String slope30) {
        return new TrendInterpreterInput.CompositePoint(
                date.toString(), new BigDecimal(value), new BigDecimal("0.10"), new BigDecimal(slope30));
    }
}
