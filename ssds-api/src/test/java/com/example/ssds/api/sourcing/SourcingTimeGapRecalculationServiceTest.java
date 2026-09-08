package com.example.ssds.api.sourcing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourcingTimeGapRecalculationServiceTest {
    private final SourcingCandidateRepository candidates = mock(SourcingCandidateRepository.class);
    private final HeatCompositeDailyRepository composites = mock(HeatCompositeDailyRepository.class);
    private final SourcingTimeGapRecalculationService service =
            new SourcingTimeGapRecalculationService(candidates, composites);

    @Test
    void selectsHighestTrendRawAndUpdatesDrivingKeywordGapAndStatus() {
        TrendKeyword slower = TrendKeyword.builder().id(31L).keyword("低糖").build();
        TrendKeyword faster = TrendKeyword.builder().id(32L).keyword("高蛋白").build();
        Product product = product(601L, slower, faster);
        SourcingCandidate candidate = SourcingCandidate.builder()
                .product(product).leadTimeDays(20).build();
        when(candidates.findEligibleForTimeGapRecalculation()).thenReturn(List.of(candidate));
        when(composites.findLatestEligibleForDrivingKeyword(anyList())).thenReturn(List.of(
                composite(slower, "0.10", "0.20", 35),
                composite(faster, "0.30", "0.10", 56)));

        int count = service.recalculateAll();

        assertAll(
                () -> assertEquals(1, count),
                () -> assertEquals(faster, candidate.getDrivingKeyword()),
                () -> assertEquals(36, candidate.getTimeGapDays()),
                () -> assertEquals(SourcingStatus.SOURCING, product.getSourcingStatus()));
        verify(candidates).saveAll(List.of(candidate));
    }

    @Test
    void clearsMaterializedGapWhenNoKeywordHasSevenDaysOfData() {
        TrendKeyword keyword = TrendKeyword.builder().id(31L).keyword("新品").build();
        Product product = product(601L, keyword);
        product.setSourcingStatus(SourcingStatus.URGENT);
        SourcingCandidate candidate = SourcingCandidate.builder()
                .product(product).drivingKeyword(keyword).leadTimeDays(20).timeGapDays(5).build();
        when(candidates.findEligibleForTimeGapRecalculationByKeywordId(31L))
                .thenReturn(List.of(candidate));
        when(composites.findLatestEligibleForDrivingKeyword(anyList())).thenReturn(List.of());

        service.recalculateAffectedByKeyword(31L);

        assertAll(
                () -> assertNull(candidate.getDrivingKeyword()),
                () -> assertNull(candidate.getTimeGapDays()),
                () -> assertEquals(SourcingStatus.PENDING, product.getSourcingStatus()));
    }

    @Test
    void negativeGapBecomesRejectedTerminalState() {
        TrendKeyword keyword = TrendKeyword.builder().id(31L).keyword("短熱度").build();
        Product product = product(601L, keyword);
        SourcingCandidate candidate = SourcingCandidate.builder()
                .product(product).leadTimeDays(20).build();
        when(candidates.findEligibleForTimeGapRecalculation()).thenReturn(List.of(candidate));
        when(composites.findLatestEligibleForDrivingKeyword(anyList()))
                .thenReturn(List.of(composite(keyword, "-0.30", "-0.20", 17)));

        service.recalculateAll();

        assertEquals(-3, candidate.getTimeGapDays());
        assertEquals(SourcingStatus.REJECTED, product.getSourcingStatus());
    }

    private static Product product(Long id, TrendKeyword... keywords) {
        return Product.builder()
                .id(id).name("候選").category(Category.builder().id(10L).name("零食").build())
                .trackType(TrackType.B).status(ProductStatus.DRAFT)
                .sourcingStatus(SourcingStatus.PENDING)
                .keywords(new LinkedHashSet<>(List.of(keywords)))
                .build();
    }

    private static HeatCompositeDaily composite(
            TrendKeyword keyword, String slope7d, String slope30d, int lifespan) {
        return HeatCompositeDaily.builder()
                .keyword(keyword).statDate(LocalDate.of(2026, 9, 3))
                .compositeValue(BigDecimal.TEN)
                .slope7d(new BigDecimal(slope7d)).slope30d(new BigDecimal(slope30d))
                .stage(HeatStage.RISING).stageWeeks((short) 1)
                .estimatedLifespanDays(lifespan).appliedWeights("{}")
                .build();
    }
}
