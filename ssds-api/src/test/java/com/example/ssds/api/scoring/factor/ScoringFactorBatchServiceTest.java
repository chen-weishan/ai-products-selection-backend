package com.example.ssds.api.scoring.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.scoring.ScoreEvaluationService.FactorInput;
import com.example.ssds.api.scoring.factor.CvrFactorProvider.CvrEvidence;
import com.example.ssds.api.scoring.factor.FactorComputationService.Evidence;
import com.example.ssds.api.scoring.factor.InventoryRiskFactorProvider.InventoryRule;
import com.example.ssds.api.scoring.factor.LogisticsRiskFactorProvider.LogisticsRule;
import com.example.ssds.api.scoring.factor.ReviewRiskFactorProvider.ReviewEvidence;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Season;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.Product;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScoringFactorBatchServiceTest {
    private static final PercentileBasis RAW = new PercentileBasis(List.of(), false, null);

    @Test
    void collectsAllRawValuesBeforeNormalizingTheCategoryPopulation() {
        ScoringFactorEvidenceLoader loader = mock(ScoringFactorEvidenceLoader.class);
        FactorComputationService computation = new FactorComputationService();
        ScoringFactorBatchService service = new ScoringFactorBatchService(loader, computation);
        Category category = Category.builder().id(10L).name("零食").build();
        List<Product> products = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 9, 18);
        for (long id = 1; id <= 10; id++) {
            Product product = Product.builder().id(id).category(category).season(Season.ALL).build();
            products.add(product);
            when(loader.loadRaw(eq(product), eq(date))).thenReturn(rawEvidence(
                    BigDecimal.valueOf(id).movePointLeft(1)));
        }

        Map<Long, Map<FactorCode, FactorInput>> result = service.prepare(products, date);

        assertDecimal("0.00", result.get(1L).get(FactorCode.MARGIN).normalizedValue());
        assertDecimal("100.00", result.get(10L).get(FactorCode.MARGIN).normalizedValue());
        assertTrue(result.values().stream()
                .map(values -> values.get(FactorCode.MARGIN))
                .noneMatch(FactorInput::imputed));
    }

    @Test
    void refreshesOnlyTheTargetWhileReusingThePreparedPopulationBases() {
        ScoringFactorEvidenceLoader loader = mock(ScoringFactorEvidenceLoader.class);
        FactorComputationService computation = new FactorComputationService();
        ScoringFactorBatchService service = new ScoringFactorBatchService(loader, computation);
        Category category = Category.builder().id(10L).name("零食").build();
        Product first = Product.builder().id(1L).category(category).season(Season.ALL).build();
        Product second = Product.builder().id(2L).category(category).season(Season.ALL).build();
        LocalDate date = LocalDate.of(2026, 9, 18);
        when(loader.loadRaw(any(), eq(date))).thenReturn(rawEvidence(new BigDecimal("0.30")));

        ScoringFactorBatchService.PreparedPopulation population =
                service.preparePopulation(List.of(first, second), date);
        service.refreshTarget(population, 1L);

        verify(loader, times(2)).loadRaw(first, date);
        verify(loader).loadRaw(second, date);
    }

    private static Evidence rawEvidence(BigDecimal margin) {
        return new Evidence(
                List.of(), RAW,
                margin, RAW,
                new CvrEvidence(List.of(), List.of()), RAW,
                null, List.of(), RAW,
                LocalDate.of(2026, 9, 18), 0, List.of(), RAW,
                new ClimateFactorProvider.ClimateEvidence(null, null, null, null, null, new BigDecimal("12")), RAW,
                new ReviewEvidence(0, 0, 0, new BigDecimal("0.15")),
                Set.of(), Month.SEPTEMBER,
                new LogisticsRule(
                        new BigDecimal("4"), new BigDecimal("4"), new BigDecimal("3"),
                        new BigDecimal("3"), new BigDecimal("10")),
                null, Season.ALL, null,
                new InventoryRule(
                        60, new BigDecimal("4"), new BigDecimal("3"), 300,
                        new BigDecimal("3"), new BigDecimal("10")));
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
