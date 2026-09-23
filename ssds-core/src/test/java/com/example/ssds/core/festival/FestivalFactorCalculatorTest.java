package com.example.ssds.core.festival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** FR-17 的 FESTIVAL 因子：時間窗四段（AC-17-2）與多節慶取最大（AC-17-6）。 */
class FestivalFactorCalculatorTest {

    @ParameterizedTest
    @CsvSource({ "-1,21,0.00", "0,21,0.50", "20,21,0.50", "21,21,1.00", "51,21,1.00", "52,21,0.00" })
    void window(long d, int leadDays, BigDecimal expected) {
        assertEquals(0, FestivalWindow.weight(d, leadDays).compareTo(expected),
                "d=" + d + " L=" + leadDays);
    }

    @Test
    void goldenCase() {
        LocalDate evaluationDate = LocalDate.of(2026, 7, 27);
        LocalDate midAutumn = evaluationDate.plusDays(60);

        Optional<FestivalFactorResult> result = FestivalFactorCalculator.evaluate(
                evaluationDate, 45,
                List.of(new FestivalAffinityInput(
                        7L, "MID_AUTUMN", "中秋節", midAutumn, new BigDecimal("0.60"))));

        assertTrue(result.isPresent());
        assertEquals(0, result.get().rawValue().compareTo(new BigDecimal("0.60")),
                "實際=" + result.get().rawValue());
        assertEquals(7L, result.get().drivingFestivalId());
    }

    @Test
    void emptyCandidatesMeansNoData() {
        assertTrue(FestivalFactorCalculator.evaluate(LocalDate.of(2026, 7, 27), 21, List.of())
                .isEmpty());
    }

    @Test
    void allOutOfWindowStillReturnsZeroNotEmpty() {
        LocalDate evaluationDate = LocalDate.of(2026, 7, 27);
        Optional<FestivalFactorResult> result = FestivalFactorCalculator.evaluate(
                evaluationDate, 21,
                List.of(new FestivalAffinityInput(
                        9L, "PAST", "已過的節慶", evaluationDate.minusDays(1),
                        new BigDecimal("0.90"))));

        assertTrue(result.isPresent(), "有建關聯就不該回 empty");
        assertEquals(0, result.get().rawValue().compareTo(BigDecimal.ZERO));
    }

    @Test
    void tieBreaksOnEarlierFestivalDate() {
        LocalDate evaluationDate = LocalDate.of(2026, 7, 27);
        Optional<FestivalFactorResult> result = FestivalFactorCalculator.evaluate(
                evaluationDate, 21,
                List.of(
                        new FestivalAffinityInput(1L, "LATE", "較晚的",
                                evaluationDate.plusDays(40), new BigDecimal("0.50")),
                        new FestivalAffinityInput(2L, "EARLY", "較早的",
                                evaluationDate.plusDays(30), new BigDecimal("0.50"))));

        assertEquals(2L, result.get().drivingFestivalId(), "並列時應取節慶日較早者");
    }
}
