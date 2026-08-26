package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.dao.SourcingHeatSignalDao;
import com.example.ssds.infra.dao.projection.SourcingHeatSignal;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.CategoryLeadTime;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.CategoryLeadTimeRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductSourcingCandidateServiceTest {

    private SourcingCandidateRepository candidateRepository;
    private CategoryLeadTimeRepository leadTimeRepository;
    private SourcingHeatSignalDao heatSignalDao;
    private ProductSourcingCandidateService service;

    @BeforeEach
    void setUp() {
        candidateRepository = mock(SourcingCandidateRepository.class);
        leadTimeRepository = mock(CategoryLeadTimeRepository.class);
        heatSignalDao = mock(SourcingHeatSignalDao.class);
        service = new ProductSourcingCandidateService(
                candidateRepository, leadTimeRepository, heatSignalDao);
    }

    @Test
    void createsCandidateAndCalculatesUrgentGapFromLatestHeat() {
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
        when(heatSignalDao.findLatest(Set.of(9L))).thenReturn(Optional.of(
                new SourcingHeatSignal(9L, HeatStage.PLATEAU, (short) 3, 35)));

        service.synchronize(product);

        ArgumentCaptor<SourcingCandidate> captor =
                ArgumentCaptor.forClass(SourcingCandidate.class);
        verify(candidateRepository).saveAndFlush(captor.capture());
        assertEquals(5, captor.getValue().getTimeGapDays());
        assertEquals(SourcingStatus.URGENT, product.getSourcingStatus());
        assertEquals(keyword, captor.getValue().getKeyword());
    }

    @Test
    void feasibleGapMovesPendingCandidateToSourcing() {
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
        when(heatSignalDao.findLatest(any())).thenReturn(Optional.of(
                new SourcingHeatSignal(9L, HeatStage.RISING, (short) 1, 56)));

        service.synchronize(product);

        assertEquals(SourcingStatus.SOURCING, product.getSourcingStatus());
    }

    @Test
    void missingNewHeatSignalClearsPreviousSignalAndTimeGap() {
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
                .heatStage(HeatStage.RISING)
                .stageWeeks((short) 2)
                .estimatedLifespanDays(56)
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
        when(heatSignalDao.findLatest(Set.of(12L))).thenReturn(Optional.empty());

        service.synchronize(product);

        assertEquals(newKeyword, existing.getKeyword());
        assertNull(existing.getHeatStage());
        assertNull(existing.getStageWeeks());
        assertNull(existing.getEstimatedLifespanDays());
        assertNull(existing.getTimeGapDays());
        verify(candidateRepository).saveAndFlush(existing);
    }
}
