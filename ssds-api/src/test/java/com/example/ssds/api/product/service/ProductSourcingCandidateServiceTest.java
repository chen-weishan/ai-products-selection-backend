package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.CategoryLeadTime;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.CategoryLeadTimeRepository;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductSourcingCandidateServiceTest {

    private SourcingCandidateRepository candidateRepository;
    private CategoryLeadTimeRepository leadTimeRepository;
    private HeatCompositeDailyRepository heatComposites;
    private ProductSourcingCandidateService service;

    @BeforeEach
    void setUp() {
        candidateRepository = mock(SourcingCandidateRepository.class);
        leadTimeRepository = mock(CategoryLeadTimeRepository.class);
        heatComposites = mock(HeatCompositeDailyRepository.class);
        service = new ProductSourcingCandidateService(
                candidateRepository, leadTimeRepository, heatComposites);
    }

    @Test
    void createsPendingCandidateAndCalculatesGapWithoutAutoPrioritizing() {
        Category category = Category.builder().id(3L).name("飲品").build();
        TrendKeyword keyword = TrendKeyword.builder().id(9L).keyword("抹茶").build();
        Product product = Product.builder()
                .id(101L)
                .category(category)
                .trackType(TrackType.B)
                .status(ProductStatus.EVALUATING)
                .sourcingStatus(SourcingStatus.PENDING)
                .keywords(new LinkedHashSet<>(Set.of(keyword)))
                .build();
        when(leadTimeRepository.findById(3L)).thenReturn(Optional.of(
                CategoryLeadTime.builder()
                        .categoryId(3L)
                        .category(category)
                        .leadTimeDays(30)
                        .build()));
        when(candidateRepository.findByProductId(101L)).thenReturn(Optional.empty());
        when(heatComposites.findLatestEligibleForDrivingKeyword(Set.of(9L)))
                .thenReturn(List.of(composite(
                        keyword, HeatStage.PLATEAU, (short) 3, 35, "0.2", "0.1")));

        service.synchronize(product);

        ArgumentCaptor<SourcingCandidate> captor =
                ArgumentCaptor.forClass(SourcingCandidate.class);
        verify(candidateRepository).saveAndFlush(captor.capture());
        assertEquals(5, captor.getValue().getTimeGapDays());
        assertEquals(SourcingStatus.PENDING, product.getSourcingStatus());
        assertEquals(keyword, captor.getValue().getKeyword());
        assertEquals(keyword, captor.getValue().getDrivingKeyword());
    }

    @Test
    void feasibleGapKeepsPendingCandidatePending() {
        Category category = Category.builder().id(3L).name("飲品").build();
        TrendKeyword keyword = TrendKeyword.builder().id(9L).keyword("抹茶").build();
        Product product = Product.builder()
                .id(101L)
                .category(category)
                .trackType(TrackType.B)
                .sourcingStatus(SourcingStatus.PENDING)
                .keywords(new LinkedHashSet<>(Set.of(keyword)))
                .build();
        when(leadTimeRepository.findById(3L)).thenReturn(Optional.of(
                CategoryLeadTime.builder().categoryId(3L).leadTimeDays(20).build()));
        when(candidateRepository.findByProductId(101L)).thenReturn(Optional.empty());
        when(heatComposites.findLatestEligibleForDrivingKeyword(any())).thenReturn(List.of(
                composite(keyword, HeatStage.RISING, (short) 1, 56, "0.2", "0.1")));

        service.synchronize(product);

        assertEquals(SourcingStatus.PENDING, product.getSourcingStatus());
    }

    @Test
    void missingNewHeatSignalPreservesPreviousGapAndStatus() {
        Category category = Category.builder().id(3L).name("飲品").build();
        TrendKeyword newKeyword = TrendKeyword.builder()
                .id(12L)
                .keyword("新品關鍵字")
                .build();
        Product product = Product.builder()
                .id(101L)
                .category(category)
                .trackType(TrackType.B)
                .sourcingStatus(SourcingStatus.SOURCING)
                .keywords(new LinkedHashSet<>(Set.of(newKeyword)))
                .build();
        SourcingCandidate existing = SourcingCandidate.builder()
                .product(product)
                .keyword(TrendKeyword.builder().id(9L).keyword("舊關鍵字").build())
                .category(category)
                .drivingKeyword(TrendKeyword.builder().id(9L).keyword("舊關鍵字").build())
                .leadTimeDays(20)
                .timeGapDays(36)
                .build();
        when(leadTimeRepository.findById(3L)).thenReturn(Optional.of(
                CategoryLeadTime.builder()
                        .categoryId(3L)
                        .category(category)
                        .leadTimeDays(20)
                        .build()));
        when(candidateRepository.findByProductId(101L))
                .thenReturn(Optional.of(existing));
        when(heatComposites.findLatestEligibleForDrivingKeyword(Set.of(12L)))
                .thenReturn(List.of());

        service.synchronize(product);

        assertEquals(9L, existing.getKeyword().getId());
        assertEquals(9L, existing.getDrivingKeyword().getId());
        assertEquals(36, existing.getTimeGapDays());
        assertEquals(SourcingStatus.SOURCING, product.getSourcingStatus());
        verify(candidateRepository).saveAndFlush(existing);
    }

    @Test
    void missingCategoryLeadTimeKeepsProductAndDefersCandidateCreation() {
        Category category = Category.builder().id(3L).name("飲品").build();
        Product product = Product.builder()
                .id(101L)
                .category(category)
                .trackType(TrackType.B)
                .sourcingStatus(SourcingStatus.PENDING)
                .keywords(new LinkedHashSet<>(Set.of(
                        TrendKeyword.builder().id(9L).keyword("抹茶").build()
                )))
                .build();
        when(leadTimeRepository.findById(3L)).thenReturn(Optional.empty());
        when(candidateRepository.findByProductId(101L)).thenReturn(Optional.empty());

        service.synchronize(product);

        assertEquals(SourcingStatus.PENDING, product.getSourcingStatus());
        verify(candidateRepository, never()).saveAndFlush(any());
        verify(heatComposites, never()).findLatestEligibleForDrivingKeyword(any());
    }

    @Test
    void rejectedCandidateUpdatesGapButKeepsRejectedStatusAndKeywordEnabled() {
        Category category = Category.builder().id(3L).name("飲品").build();
        TrendKeyword keyword = TrendKeyword.builder().id(9L).keyword("抹茶").enabled(true).build();
        Product product = Product.builder()
                .id(101L)
                .category(category)
                .trackType(TrackType.B)
                .sourcingStatus(SourcingStatus.REJECTED)
                .keywords(new LinkedHashSet<>(Set.of(keyword)))
                .build();
        SourcingCandidate rejected = SourcingCandidate.builder()
                .id(201L)
                .product(product)
                .category(category)
                .drivingKeyword(keyword)
                .leadTimeDays(20)
                .timeGapDays(-3)
                .build();
        when(candidateRepository.findByProductId(101L)).thenReturn(Optional.of(rejected));
        when(leadTimeRepository.findById(3L)).thenReturn(Optional.of(
                CategoryLeadTime.builder().categoryId(3L).category(category).leadTimeDays(20).build()));
        when(heatComposites.findLatestEligibleForDrivingKeyword(Set.of(9L)))
                .thenReturn(List.of(composite(
                        keyword, HeatStage.RISING, (short) 1, 56, "0.2", "0.1")));

        service.synchronize(product);

        assertEquals(36, rejected.getTimeGapDays());
        assertEquals(SourcingStatus.REJECTED, product.getSourcingStatus());
        assertEquals(true, keyword.isEnabled());
        verify(candidateRepository).saveAndFlush(rejected);
    }

    private static HeatCompositeDaily composite(
            TrendKeyword keyword,
            HeatStage stage,
            short stageWeeks,
            int lifespanDays,
            String slope7d,
            String slope30d) {
        return HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(LocalDate.of(2026, 9, 30))
                .compositeValue(BigDecimal.TEN)
                .slope7d(new BigDecimal(slope7d))
                .slope30d(new BigDecimal(slope30d))
                .stage(stage)
                .stageWeeks(stageWeeks)
                .estimatedLifespanDays(lifespanDays)
                .appliedWeights("{}")
                .build();
    }
}
