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
        product.setSourcingStatus(SourcingStatus.SOURCING);
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
    void preservesLastSnapshotWhenNoKeywordHasSevenDaysOfData() {
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
                () -> assertEquals(keyword, candidate.getDrivingKeyword()),
                () -> assertEquals(5, candidate.getTimeGapDays()),
                () -> assertEquals(SourcingStatus.URGENT, product.getSourcingStatus()));
    }

    @Test
    void negativeGapBecomesRejectedTerminalState() {
        TrendKeyword keyword = TrendKeyword.builder().id(31L).keyword("短熱度").build();
        Product product = product(601L, keyword);
        product.setSourcingStatus(SourcingStatus.SOURCING);
        SourcingCandidate candidate = SourcingCandidate.builder()
                .product(product).leadTimeDays(20).build();
        when(candidates.findEligibleForTimeGapRecalculation()).thenReturn(List.of(candidate));
        when(composites.findLatestEligibleForDrivingKeyword(anyList()))
                .thenReturn(List.of(composite(keyword, "-0.30", "-0.20", 17)));

        service.recalculateAll();

        assertEquals(-3, candidate.getTimeGapDays());
        assertEquals(SourcingStatus.REJECTED, product.getSourcingStatus());
        assertTrue(keyword.isEnabled());
    }

    @Test
    void pendingCandidateUpdatesGapButNeverChangesStatus() {
        TrendKeyword keyword = TrendKeyword.builder().id(31L).keyword("待觀察").build();
        Product product = product(601L, keyword);
        SourcingCandidate candidate = SourcingCandidate.builder()
                .product(product).leadTimeDays(20).build();
        when(candidates.findEligibleForTimeGapRecalculation()).thenReturn(List.of(candidate));
        when(composites.findLatestEligibleForDrivingKeyword(anyList()))
                .thenReturn(List.of(composite(keyword, "-0.30", "-0.20", 17)));

        service.recalculateAll();

        assertAll(
                () -> assertEquals(-3, candidate.getTimeGapDays()),
                () -> assertEquals(SourcingStatus.PENDING, product.getSourcingStatus()));
    }

    @Test
    void acceptsLatestCompositeWithoutThirtyDaySlope() {
        TrendKeyword keyword = TrendKeyword.builder().id(31L).keyword("短期趨勢").build();
        Product product = product(601L, keyword);
        SourcingCandidate candidate = SourcingCandidate.builder()
                .product(product).leadTimeDays(20).build();
        when(candidates.findEligibleForTimeGapRecalculation()).thenReturn(List.of(candidate));
        when(composites.findLatestEligibleForDrivingKeyword(anyList()))
                .thenReturn(List.of(composite(keyword, "0.30", null, 42)));

        service.recalculateAll();

        assertAll(
                () -> assertEquals(keyword, candidate.getDrivingKeyword()),
                () -> assertEquals(22, candidate.getTimeGapDays()));
    }

    @Test
    void ignoresDisabledAndOlderKeywordSignals() {
        TrendKeyword disabledHot = TrendKeyword.builder()
                .id(31L).keyword("停用高熱").enabled(false).build();
        TrendKeyword enabledOld = TrendKeyword.builder()
                .id(32L).keyword("舊資料").enabled(true).build();
        TrendKeyword enabledFresh = TrendKeyword.builder()
                .id(33L).keyword("今日資料").enabled(true).build();
        Product product = product(601L, disabledHot, enabledOld, enabledFresh);
        SourcingCandidate candidate = SourcingCandidate.builder()
                .product(product).leadTimeDays(20).build();
        when(candidates.findEligibleForTimeGapRecalculation()).thenReturn(List.of(candidate));
        when(composites.findLatestEligibleForDrivingKeyword(anyList())).thenReturn(List.of(
                composite(disabledHot, LocalDate.of(2026, 9, 30), "1.00", "1.00", 99),
                composite(enabledOld, LocalDate.of(2026, 9, 29), "0.90", "0.90", 80),
                composite(enabledFresh, LocalDate.of(2026, 9, 30), "0.10", "0.10", 42)));

        service.recalculateAll();

        assertAll(
                () -> assertEquals(enabledFresh, candidate.getDrivingKeyword()),
                () -> assertEquals(22, candidate.getTimeGapDays()));
    }

    @Test
    void sharedEnabledKeywordContinuesUpdatingAllCandidatesAfterRejection() {
        TrendKeyword shared = TrendKeyword.builder()
                .id(31L).keyword("巧克力").enabled(true).build();
        Product first = product(601L, shared);
        first.setSourcingStatus(SourcingStatus.SOURCING);
        Product second = product(602L, shared);
        second.setSourcingStatus(SourcingStatus.SOURCING);
        SourcingCandidate rejecting = SourcingCandidate.builder()
                .product(first).leadTimeDays(20).build();
        SourcingCandidate unaffected = SourcingCandidate.builder()
                .product(second).drivingKeyword(shared).leadTimeDays(20)
                .timeGapDays(36).build();
        when(candidates.findEligibleForTimeGapRecalculation())
                .thenReturn(List.of(rejecting, unaffected));
        when(composites.findLatestEligibleForDrivingKeyword(anyList()))
                .thenReturn(List.of(composite(shared, "-0.30", "-0.20", 17)));

        service.recalculateAll();

        assertAll(
                () -> assertEquals(SourcingStatus.REJECTED, first.getSourcingStatus()),
                () -> assertTrue(shared.isEnabled()),
                () -> assertEquals(SourcingStatus.REJECTED, second.getSourcingStatus()),
                () -> assertEquals(-3, unaffected.getTimeGapDays()));
    }

    @Test
    void defersBaselineStatusDecisionUntilDrivingKeywordAgentResultArrives() {
        TrendKeyword keyword = TrendKeyword.builder()
                .id(31L).keyword("日本和牛").enabled(true).build();
        Product product = product(601L, keyword);
        product.setSourcingStatus(SourcingStatus.SOURCING);
        SourcingCandidate candidate = SourcingCandidate.builder()
                .product(product).drivingKeyword(keyword).leadTimeDays(21)
                .timeGapDays(35).build();
        when(candidates.findEligibleForTimeGapRecalculation()).thenReturn(List.of(candidate));
        when(candidates.findEligibleForTimeGapRecalculationByKeywordId(31L))
                .thenReturn(List.of(candidate));
        when(composites.findLatestEligibleForDrivingKeyword(anyList()))
                .thenReturn(
                        List.of(composite(keyword, "-0.45", "-0.24", 17)),
                        List.of(composite(keyword, "-0.45", "-0.24", 42)));

        int baselineCount = service.recalculateAllExceptDrivingKeywords(Set.of(31L));

        assertAll(
                () -> assertEquals(0, baselineCount),
                () -> assertEquals(35, candidate.getTimeGapDays()),
                () -> assertEquals(SourcingStatus.SOURCING, product.getSourcingStatus()));

        int agentCount = service.recalculateAffectedByKeyword(31L);

        assertAll(
                () -> assertEquals(1, agentCount),
                () -> assertEquals(21, candidate.getTimeGapDays()),
                () -> assertEquals(SourcingStatus.SOURCING, product.getSourcingStatus()));
    }

    @Test
    void nonDrivingKeywordAgentCompletionCannotApplyDrivingKeywordBaselineEarly() {
        TrendKeyword nonDriving = TrendKeyword.builder()
                .id(31L).keyword("一般詞").enabled(true).build();
        TrendKeyword driving = TrendKeyword.builder()
                .id(32L).keyword("生效詞").enabled(true).build();
        Product product = product(601L, nonDriving, driving);
        product.setSourcingStatus(SourcingStatus.URGENT);
        SourcingCandidate candidate = SourcingCandidate.builder()
                .product(product).drivingKeyword(driving).leadTimeDays(20)
                .timeGapDays(5).build();
        when(candidates.findEligibleForTimeGapRecalculationByKeywordId(31L))
                .thenReturn(List.of(candidate));
        when(composites.findLatestEligibleForDrivingKeyword(anyList())).thenReturn(List.of(
                composite(nonDriving, "0.10", "0.10", 42),
                composite(driving, "0.80", "0.80", 17)));

        int count = service.recalculateAffectedByKeyword(31L);

        assertAll(
                () -> assertEquals(0, count),
                () -> assertEquals(5, candidate.getTimeGapDays()),
                () -> assertEquals(SourcingStatus.URGENT, product.getSourcingStatus()));
    }

    @Test
    void priorRejectedCandidateStillDoesNotReviveFromPositiveAgentResult() {
        TrendKeyword keyword = TrendKeyword.builder()
                .id(31L).keyword("既有淘汰").enabled(true).build();
        Product product = product(601L, keyword);
        product.setSourcingStatus(SourcingStatus.REJECTED);
        SourcingCandidate candidate = SourcingCandidate.builder()
                .product(product).drivingKeyword(keyword).leadTimeDays(20)
                .timeGapDays(-3).build();
        when(candidates.findEligibleForTimeGapRecalculationByKeywordId(31L))
                .thenReturn(List.of(candidate));
        when(composites.findLatestEligibleForDrivingKeyword(anyList()))
                .thenReturn(List.of(composite(keyword, "0.30", "0.20", 56)));

        service.recalculateAffectedByKeyword(31L);

        assertAll(
                () -> assertEquals(36, candidate.getTimeGapDays()),
                () -> assertEquals(SourcingStatus.REJECTED, product.getSourcingStatus()));
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
        return composite(keyword, LocalDate.of(2026, 9, 3), slope7d, slope30d, lifespan);
    }

    private static HeatCompositeDaily composite(
            TrendKeyword keyword,
            LocalDate statDate,
            String slope7d,
            String slope30d,
            int lifespan) {
        return HeatCompositeDaily.builder()
                .keyword(keyword).statDate(statDate)
                .compositeValue(BigDecimal.TEN)
                .slope7d(new BigDecimal(slope7d))
                .slope30d(slope30d == null ? null : new BigDecimal(slope30d))
                .stage(HeatStage.RISING).stageWeeks((short) 1)
                .estimatedLifespanDays(lifespan).appliedWeights("{}")
                .build();
    }
}
