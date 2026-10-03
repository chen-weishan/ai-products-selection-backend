package com.example.ssds.api.scoring.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssds.api.scoring.factor.CvrFactorProvider.CvrEvidence;
import com.example.ssds.api.scoring.factor.CvrFactorProvider.SalesSample;
import com.example.ssds.api.scoring.factor.FestivalFactorProvider.FestivalAffinity;
import com.example.ssds.api.scoring.factor.FactorComputationService.Evidence;
import com.example.ssds.api.scoring.factor.InventoryRiskFactorProvider.InventoryRule;
import com.example.ssds.api.scoring.factor.LogisticsRiskFactorProvider.LogisticsRule;
import com.example.ssds.api.scoring.factor.PriceFitFactorProvider.AudienceBand;
import com.example.ssds.api.scoring.factor.ReviewRiskFactorProvider.ReviewEvidence;
import com.example.ssds.api.scoring.factor.TrendFactorProvider.KeywordTrend;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.LogisticsCondition;
import com.example.ssds.core.domain.Season;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FactorProvidersTest {
    private static final PercentileBasis BASIS = new PercentileBasis(
            List.of(bd("0.10"), bd("0.20"), bd("0.30")), false, null);

    @Test
    void percentileUsesAverageRankForTies() {
        PercentileBasis basis = new PercentileBasis(
                List.of(bd("10"), bd("20"), bd("40")), false, null);

        assertDecimal("50.00", basis.normalize(bd("20")));
    }

    @Test
    void marginUsesRawRateAndMarksFallbackBasisAsImputed() {
        FactorComputation result = new MarginFactorProvider().provide(
                bd("0.25"), new PercentileBasis(List.of(bd("0.10"), bd("0.20")), true, "全品類基準"));

        assertTrue(result.dataAvailable());
        assertTrue(result.imputed());
        assertDecimal("100.00", result.normalizedValue());
        assertFalse(new MarginFactorProvider().provide(null, BASIS).dataAvailable());
    }

    @Test
    void trendChoosesHighestKeywordAndHonorsVolumeFloor() {
        TrendFactorProvider provider = new TrendFactorProvider();
        FactorComputation selected = provider.provide(List.of(
                new KeywordTrend(1L, "低", bd("0.10"), bd("0.10"), false),
                new KeywordTrend(2L, "高", bd("0.40"), bd("0.20"), false)), BASIS);
        assertDecimal("0.3400", selected.rawValue());
        assertTrue(selected.note().contains("高"));
        assertEquals(2L, selected.drivingKeywordId());

        FactorComputation floored = provider.provide(List.of(
                new KeywordTrend(3L, "量級低", bd("0.40"), bd("0.20"), true)), BASIS);
        assertTrue(floored.dataAvailable());
        assertDecimal("0.00", floored.normalizedValue());
        assertFalse(provider.provide(List.of(), BASIS).dataAvailable());
    }

    @Test
    void cvrUsesOwnRateThenCategoryMedianAndRelativeQtyFallback() {
        CvrFactorProvider provider = new CvrFactorProvider();
        FactorComputation own = provider.provide(new CvrEvidence(
                List.of(new SalesSample(42, 1000)), tenHistoriesWithImpressions()), BASIS);
        assertDecimal("0.042000", own.rawValue());
        assertFalse(own.imputed());

        FactorComputation median = provider.provide(new CvrEvidence(
                List.of(), tenHistoriesWithImpressions()), BASIS);
        assertTrue(median.dataAvailable());
        assertTrue(median.imputed());

        List<List<SalesSample>> quantities = new ArrayList<>();
        for (int i = 0; i < 10; i++) quantities.add(List.of(new SalesSample(10, null)));
        FactorComputation relative = provider.provide(new CvrEvidence(
                List.of(new SalesSample(20, null)), quantities), BASIS);
        assertDecimal("2.000000", relative.rawValue());

        assertFalse(provider.provide(new CvrEvidence(List.of(), List.of()), BASIS).dataAvailable());
    }

    @Test
    void priceFitMatchesSpecificationGoldenCaseAndRejectsInvalidShares() {
        PriceFitFactorProvider provider = new PriceFitFactorProvider();
        List<AudienceBand> bands = List.of(
                new AudienceBand("A", bd("120"), bd("180"), bd("0.6")),
                new AudienceBand("B", bd("60"), bd("120"), bd("0.3")),
                new AudienceBand("C", bd("180"), bd("400"), bd("0.1")));

        FactorComputation result = provider.provide(bd("149"), bands, BASIS);
        assertDecimal("0.681818", result.rawValue());
        assertFalse(provider.provide(null, bands, BASIS).dataAvailable());
        assertThrows(IllegalArgumentException.class, () -> provider.provide(
                bd("149"), List.of(new AudienceBand("A", bd("1"), bd("2"), bd("0.5"))), BASIS));
    }

    @Test
    void festivalWindowCoversAllBoundariesAndChoosesMaximum() {
        assertDecimal("0", FestivalFactorProvider.window(76, 45));
        assertDecimal("1", FestivalFactorProvider.window(75, 45));
        assertDecimal("1", FestivalFactorProvider.window(45, 45));
        assertDecimal("0.5", FestivalFactorProvider.window(44, 45));
        assertDecimal("0.5", FestivalFactorProvider.window(0, 45));
        assertDecimal("0", FestivalFactorProvider.window(-1, 45));

        LocalDate date = LocalDate.of(2026, 7, 1);
        FactorComputation result = new FestivalFactorProvider().provide(date, 45, List.of(
                new FestivalAffinity(10L, "A", "低關聯", date.plusDays(60), bd("0.2")),
                new FestivalAffinity(11L, "B", "高關聯", date.plusDays(60), bd("0.6"))), BASIS);
        assertDecimal("0.6000", result.rawValue());
        assertTrue(result.note().contains("高關聯"));
        assertEquals(11L, result.drivingFestivalId());
    }

    @Test
    void climateMatchesGoldenCaseAndUsesCategoryFallback() {
        ClimateFactorProvider provider = new ClimateFactorProvider();
        FactorComputation direct = provider.provide(new ClimateFactorProvider.ClimateEvidence(
                bd("29.4"), bd("18"), bd("26"), null, null, bd("12")), BASIS);
        assertDecimal("0.716667", direct.rawValue());
        assertFalse(direct.imputed());

        FactorComputation fallback = provider.provide(new ClimateFactorProvider.ClimateEvidence(
                bd("20"), null, null, bd("18"), bd("26"), bd("12")), BASIS);
        assertDecimal("1.000000", fallback.rawValue());
        assertTrue(fallback.imputed());

        assertFalse(provider.provide(new ClimateFactorProvider.ClimateEvidence(
                bd("20"), null, null, null, null, bd("12")), BASIS).dataAvailable());
    }

    @Test
    void reviewRiskAppliesSampleThresholdAndAllPenaltyBands() {
        ReviewRiskFactorProvider provider = new ReviewRiskFactorProvider();
        assertFalse(provider.provide(new ReviewEvidence(19, 10, 8, bd("0.15"))).dataAvailable());
        assertDecimal("0.0", provider.provide(new ReviewEvidence(20, 3, 3, bd("0.15"))).penaltyValue());
        assertDecimal("8.0", provider.provide(new ReviewEvidence(20, 4, 1, bd("0.15"))).penaltyValue());
        assertDecimal("14.0", provider.provide(new ReviewEvidence(20, 4, 2, bd("0.15"))).penaltyValue());
        assertDecimal("20.0", provider.provide(new ReviewEvidence(20, 4, 3, bd("0.15"))).penaltyValue());
    }

    @Test
    void logisticsRiskUsesRuntimeRuleAndCapsTotal() {
        LogisticsRiskFactorProvider provider = new LogisticsRiskFactorProvider();
        LogisticsRule rule = new LogisticsRule(bd("4"), bd("4"), bd("3"), bd("3"), bd("10"));
        FactorComputation summer = provider.provide(EnumSet.of(
                LogisticsCondition.MELTABLE,
                LogisticsCondition.FROZEN,
                LogisticsCondition.FRAGILE), Month.AUGUST, rule);
        assertDecimal("10.0", summer.penaltyValue());
        assertTrue(summer.note().contains("夏季易融化"));

        FactorComputation winter = provider.provide(
                EnumSet.of(LogisticsCondition.MELTABLE), Month.JANUARY, rule);
        assertDecimal("0.0", winter.penaltyValue());
        assertFalse(provider.provide(Set.of(), Month.JANUARY, rule).dataAvailable());
    }

    @Test
    void inventoryRiskUsesStrictThresholdsAndCapsTotal() {
        InventoryRiskFactorProvider provider = new InventoryRiskFactorProvider();
        InventoryRule rule = new InventoryRule(60, bd("4"), bd("3"), 300, bd("3"), bd("10"));

        assertDecimal("0.0", provider.provide(60, Season.ALL, 300, rule).penaltyValue());
        assertDecimal("10.0", provider.provide(30, Season.SUMMER, 301, rule).penaltyValue());
        assertFalse(provider.provide(null, null, null, rule).dataAvailable());
    }

    @Test
    void inputSetRequiresEachFactorExactlyOnceAndProducesEvaluationShape() {
        List<FactorComputation> computations = new ArrayList<>();
        for (FactorCode code : FactorCode.values()) {
            computations.add(code.isPenalty()
                    ? FactorComputation.penalty(code, null, BigDecimal.ZERO, true, null)
                    : FactorComputation.bonus(code, bd("0.2"), BASIS, false, null));
        }
        Map<?, ?> result = FactorInputSet.from(computations);
        assertEquals(9, result.size());

        computations.remove(0);
        assertThrows(IllegalArgumentException.class, () -> FactorInputSet.from(computations));
    }

    @Test
    void computationServiceProducesACompleteDeterministicEvaluationInput() {
        LogisticsRule logisticsRule = new LogisticsRule(bd("4"), bd("4"), bd("3"), bd("3"), bd("10"));
        InventoryRule inventoryRule = new InventoryRule(60, bd("4"), bd("3"), 300, bd("3"), bd("10"));
        LocalDate date = LocalDate.of(2026, 8, 1);
        Evidence evidence = new Evidence(
                List.of(new KeywordTrend(1L, "trend", bd("0.2"), bd("0.1"), false)), BASIS,
                bd("0.3"), BASIS,
                new CvrEvidence(List.of(new SalesSample(4, 100)), tenHistoriesWithImpressions()), BASIS,
                bd("149"), List.of(new AudienceBand("MAIN", bd("120"), bd("180"), bd("1"))), BASIS,
                date, 45, List.of(new FestivalAffinity(20L, "MID", "中秋", date.plusDays(60), bd("0.6"))), BASIS,
                new ClimateFactorProvider.ClimateEvidence(
                        bd("29.4"), bd("18"), bd("26"), null, null, bd("12")), BASIS,
                new ReviewEvidence(20, 2, 1, bd("0.15")),
                EnumSet.of(LogisticsCondition.MELTABLE), Month.AUGUST, logisticsRule,
                180, Season.ALL, 200, inventoryRule);

        Map<FactorCode, ?> first = new FactorComputationService().compute(evidence);
        Map<FactorCode, ?> second = new FactorComputationService().compute(evidence);

        assertEquals(9, first.size());
        assertEquals(first, second);
    }

    private static List<List<SalesSample>> tenHistoriesWithImpressions() {
        List<List<SalesSample>> histories = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            histories.add(List.of(new SalesSample(i, 100)));
        }
        return histories;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }
}
