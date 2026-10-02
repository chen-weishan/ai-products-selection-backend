package com.example.ssds.api.sourcing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SourcingPriorityCommandServiceTest {
    private final SourcingCandidateRepository candidates = mock(SourcingCandidateRepository.class);
    private final HeatCompositeDailyRepository composites = mock(HeatCompositeDailyRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final SourcingPriorityCommandService service =
            new SourcingPriorityCommandService(candidates, composites, audits, users);

    @Test
    void watchRevivesRejectedCandidateKeepsLiveDataAndKeywordStateAndWritesAudit() {
        TrendKeyword shared = keyword(31L, false);
        TrendKeyword specific = keyword(32L, false);
        SourcingCandidate candidate = candidate(SourcingStatus.REJECTED, shared, specific);
        candidate.setDrivingKeyword(shared);
        candidate.setTimeGapDays(-3);
        load(candidate);

        var response = service.watch(601L, "buyer@example.com");

        assertAll(
                () -> assertEquals(SourcingStatus.PENDING, response.sourcingStatus()),
                () -> assertEquals(ProductStatus.WATCHING, candidate.getProduct().getStatus()),
                () -> assertEquals(-3, response.timeGapDays()),
                () -> assertEquals(shared, candidate.getDrivingKeyword()),
                () -> assertFalse(shared.isEnabled()),
                () -> assertFalse(specific.isEnabled()));
        verify(candidates).save(candidate);
        verify(audits).save(any(AuditLog.class));
    }

    @Test
    void watchExistingActiveCandidateReturnsToPendingWithoutDiscardingCurrentGap() {
        TrendKeyword keyword = keyword(31L, true);
        SourcingCandidate candidate = candidate(SourcingStatus.URGENT, keyword);
        candidate.setDrivingKeyword(keyword);
        candidate.setTimeGapDays(5);
        load(candidate);

        var response = service.watch(601L, "buyer@example.com");

        assertAll(
                () -> assertEquals(SourcingStatus.PENDING, response.sourcingStatus()),
                () -> assertEquals(ProductStatus.WATCHING, candidate.getProduct().getStatus()),
                () -> assertEquals(5, response.timeGapDays()));
        verify(audits, never()).save(any());
    }

    @Test
    void prioritizeRejectsNegativeCurrentGapWithoutChangingCandidateOrKeywords() {
        TrendKeyword shared = keyword(31L, true);
        TrendKeyword specific = keyword(32L, true);
        SourcingCandidate candidate = candidate(SourcingStatus.PENDING, shared, specific);
        currentComposite(candidate, shared, 17);
        load(candidate);

        assertInvalid(() -> service.prioritize(601L));

        assertAll(
                () -> assertEquals(SourcingStatus.PENDING, candidate.getProduct().getSourcingStatus()),
                () -> assertTrue(shared.isEnabled()),
                () -> assertTrue(specific.isEnabled()));
        verify(candidates, never()).save(candidate);
    }

    @Test
    void prioritizeUsesThresholdsOnlyAfterExplicitCommand() {
        TrendKeyword keyword = keyword(31L, true);
        SourcingCandidate urgent = candidate(SourcingStatus.PENDING, keyword);
        currentComposite(urgent, keyword, 34);
        load(urgent);

        assertEquals(SourcingStatus.URGENT, service.prioritize(601L).sourcingStatus());

        SourcingCandidate sourcing = candidate(SourcingStatus.PENDING, keyword);
        currentComposite(sourcing, keyword, 35);
        load(sourcing);
        assertEquals(SourcingStatus.SOURCING, service.prioritize(601L).sourcingStatus());
    }

    @Test
    void repeatedPrioritizeRecalculatesTheSameCandidateWithoutCreatingAnotherOne() {
        TrendKeyword keyword = keyword(31L, true);
        SourcingCandidate candidate = candidate(SourcingStatus.PENDING, keyword);
        currentComposite(candidate, keyword, 35);
        load(candidate);

        var first = service.prioritize(601L);
        var second = service.prioritize(601L);

        assertAll(
                () -> assertEquals(SourcingStatus.SOURCING, first.sourcingStatus()),
                () -> assertEquals(SourcingStatus.SOURCING, second.sourcingStatus()),
                () -> assertSame(candidate, candidates.findDetailedByProductId(601L).orElseThrow()));
        verify(candidates, times(2)).save(candidate);
    }

    @Test
    void prioritizeRejectsMissingOrStaleGapAndTerminalState() {
        TrendKeyword keyword = keyword(31L, true);
        SourcingCandidate missing = candidate(SourcingStatus.PENDING, keyword);
        load(missing);
        assertInvalid(() -> service.prioritize(601L));

        SourcingCandidate stale = candidate(SourcingStatus.PENDING, keyword);
        stale.setDrivingKeyword(keyword);
        stale.setTimeGapDays(10);
        when(candidates.findDetailedByProductId(601L)).thenReturn(Optional.of(stale));
        when(composites.findFirstByKeywordIdOrderByStatDateDesc(31L))
                .thenReturn(Optional.of(composite(keyword, 35)));
        assertInvalid(() -> service.prioritize(601L));

        SourcingCandidate rejected = candidate(SourcingStatus.REJECTED, keyword);
        rejected.setTimeGapDays(-3);
        load(rejected);
        assertInvalid(() -> service.prioritize(601L));
    }

    @Test
    void capabilitiesUseLiveCompositeAndAuthoritativeButtonRules() {
        TrendKeyword keyword = keyword(31L, true);
        SourcingCandidate fresh = candidate(SourcingStatus.PENDING, keyword);
        HeatCompositeDaily live = currentComposite(fresh, keyword, 35);

        var available = service.capabilities(fresh, live);

        assertAll(
                () -> assertTrue(available.canWatch()),
                () -> assertTrue(available.canPrioritize()),
                () -> assertNull(available.prioritizeDisabledReason()));

        SourcingCandidate rejected = candidate(SourcingStatus.REJECTED, keyword);
        rejected.setTimeGapDays(-3);
        var blocked = service.capabilities(rejected, composite(keyword, 17));
        assertAll(
                () -> assertTrue(blocked.canWatch()),
                () -> assertFalse(blocked.canPrioritize()),
                () -> assertTrue(blocked.prioritizeDisabledReason().contains("已淘汰")));
    }

    private HeatCompositeDaily currentComposite(
            SourcingCandidate candidate, TrendKeyword keyword, int lifespan) {
        candidate.setDrivingKeyword(keyword);
        candidate.setTimeGapDays(lifespan - candidate.getLeadTimeDays());
        HeatCompositeDaily composite = composite(keyword, lifespan);
        when(composites.findFirstByKeywordIdOrderByStatDateDesc(keyword.getId()))
                .thenReturn(Optional.of(composite));
        return composite;
    }

    private void load(SourcingCandidate candidate) {
        when(candidates.findDetailedByProductId(601L)).thenReturn(Optional.of(candidate));
    }

    private static SourcingCandidate candidate(
            SourcingStatus status, TrendKeyword... keywords) {
        Product product = Product.builder()
                .id(601L).name("候選").category(Category.builder().id(10L).name("零食").build())
                .trackType(TrackType.B).status(ProductStatus.DRAFT).sourcingStatus(status)
                .keywords(new LinkedHashSet<>(Set.of(keywords))).build();
        return SourcingCandidate.builder().id(71L).product(product)
                .category(product.getCategory()).leadTimeDays(20).build();
    }

    private static TrendKeyword keyword(Long id, boolean enabled) {
        return TrendKeyword.builder().id(id).keyword("關鍵字" + id).enabled(enabled).build();
    }

    private static HeatCompositeDaily composite(TrendKeyword keyword, int lifespan) {
        return HeatCompositeDaily.builder().keyword(keyword).statDate(LocalDate.of(2026, 9, 30))
                .stage(HeatStage.RISING).stageWeeks((short) 1)
                .estimatedLifespanDays(lifespan).appliedWeights("{}").build();
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, exception.getErrorCode());
    }
}
