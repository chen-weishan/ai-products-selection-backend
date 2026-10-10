package com.example.ssds.api.sourcing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.agent.SourcingScoutAgent;
import com.example.ssds.ai.model.sourcing.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.api.aitask.service.AiTaskService;
import com.example.ssds.api.aitask.dto.AiTaskResponse;
import com.example.ssds.api.sourcing.dto.SourcingScoutRequest;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SourcingScoutServiceTest {
    @Test
    void scoutOnlyStoresAgentSixReportAndDoesNotChangeTimeGapAuthorityFields() {
        Fixture fixture = new Fixture();
        fixture.candidate.setTimeGapDays(15);
        fixture.candidate.setDrivingKeyword(fixture.keyword);
        when(fixture.agent.scout(any(), eq(false))).thenReturn(result());

        var response = fixture.service.scout(601L, false);

        assertAll(
                () -> assertEquals("探索報告內容需超過二十個字以符合結構驗證", fixture.candidate.getScoutReport()),
                () -> assertEquals(15, fixture.candidate.getTimeGapDays()),
                () -> assertEquals(fixture.keyword, fixture.candidate.getDrivingKeyword()),
                () -> assertEquals(SourcingStatus.PENDING, fixture.candidate.getProduct().getSourcingStatus()),
                () -> assertEquals(SourcingStatus.PENDING, response.sourcingStatus()),
                () -> assertNull(response.heatStage()),
                () -> assertEquals(31L, response.drivingKeywordId()));
        verify(fixture.candidates).save(fixture.candidate);
    }

    @Test
    void startReusesExistingBTrackProductForKeywordAndCategory() {
        Fixture fixture = new Fixture();
        when(fixture.categories.findById(10L)).thenReturn(Optional.of(fixture.category));
        when(fixture.leadTimes.findById(10L)).thenReturn(Optional.of(fixture.leadTime));
        when(fixture.keywords.findByKeyword("低糖零食")).thenReturn(Optional.of(fixture.keyword));
        when(fixture.products.findReusableSourcingProduct(31L, 10L))
                .thenReturn(Optional.of(fixture.candidate.getProduct()));
        when(fixture.tasks.createSourcingScout(
                "低糖零食", fixture.category, fixture.candidate.getProduct(), false))
                .thenReturn(mock(AiTaskResponse.class));

        fixture.service.start(new SourcingScoutRequest(" 低糖零食 ", 10L, false));

        verify(fixture.products, never()).save(any());
        verify(fixture.candidates, never()).save(any());
        verify(fixture.tasks).createSourcingScout(
                "低糖零食", fixture.category, fixture.candidate.getProduct(), false);
    }

    @Test
    void startUsesCanonicalKeywordForLookupAndNewRecords() {
        Fixture fixture = new Fixture();
        when(fixture.categories.findById(10L)).thenReturn(Optional.of(fixture.category));
        when(fixture.leadTimes.findById(10L)).thenReturn(Optional.of(fixture.leadTime));
        when(fixture.keywords.findByKeyword("organic snack")).thenReturn(Optional.of(fixture.keyword));
        when(fixture.products.findReusableSourcingProduct(31L, 10L))
                .thenReturn(Optional.of(fixture.candidate.getProduct()));
        when(fixture.tasks.createSourcingScout(
                "organic snack", fixture.category, fixture.candidate.getProduct(), false))
                .thenReturn(mock(AiTaskResponse.class));

        fixture.service.start(new SourcingScoutRequest(" Ｏｒｇａｎｉｃ　 SNACK ", 10L, false));

        verify(fixture.keywords).findByKeyword("organic snack");
        verify(fixture.products).findReusableSourcingProduct(31L, 10L);
    }

    @Test
    void scoutExposesAgentCacheHitForTaskAccounting() {
        Fixture fixture = new Fixture();
        SourcingScoutResult cached = new SourcingScoutResult(
                result().output(), true, "test-model", "scout-v6", 10, 5, 0);
        when(fixture.agent.scout(any(), eq(false))).thenReturn(cached);

        var response = fixture.service.scout(601L, false);

        assertTrue(response.cacheHit());
    }

    @Test
    void startUnknownKeywordCreatesOnlyRawScoutTaskAndNoMasterData() {
        Fixture fixture = new Fixture();
        when(fixture.categories.findById(10L)).thenReturn(Optional.of(fixture.category));
        when(fixture.leadTimes.findById(10L)).thenReturn(Optional.of(fixture.leadTime));
        when(fixture.keywords.findByKeyword("新品關鍵字")).thenReturn(Optional.empty());
        when(fixture.tasks.createSourcingScout(
                "新品關鍵字", fixture.category, null, true)).thenReturn(mock(AiTaskResponse.class));

        fixture.service.start(new SourcingScoutRequest("新品關鍵字", 10L, true));

        verify(fixture.keywords, never()).save(any());
        verify(fixture.products, never()).save(any());
        verify(fixture.candidates, never()).save(any());
        verify(fixture.tasks).createSourcingScout("新品關鍵字", fixture.category, null, true);
    }

    @Test
    void scoutRawItemStoresReportWithoutCreatingProduct() {
        Fixture fixture = new Fixture();
        AiTaskItem item = AiTaskItem.builder()
                .id(731L)
                .task(AiTask.builder().id(730L).taskType(AiTaskType.SOURCING_SCOUT).build())
                .scoutKeyword("陌生字詞")
                .scoutCategory(fixture.category)
                .build();
        when(fixture.agent.scout(any(), eq(false))).thenReturn(result());

        var response = fixture.service.scout(item, false);

        assertAll(
                () -> assertEquals(731L, response.itemId()),
                () -> assertNull(response.productId()),
                () -> assertEquals(10L, response.categoryId()),
                () -> assertEquals("探索報告內容需超過二十個字以符合結構驗證", response.report()),
                () -> assertNull(response.timeGapDays()),
                () -> assertNull(response.sourcingStatus()),
                () -> assertTrue(response.canWatch()),
                () -> assertFalse(response.canPrioritize()),
                () -> assertEquals("test-model", item.getScoutModel()));
        verify(fixture.products, never()).save(any());
        verify(fixture.candidates, never()).save(any());
    }

    @Test
    void latestRawResultLoadsByTaskItemIdWithoutProduct() {
        Fixture fixture = new Fixture();
        AiTaskItem item = AiTaskItem.builder()
                .id(731L)
                .task(AiTask.builder().id(730L).taskType(AiTaskType.SOURCING_SCOUT).build())
                .scoutKeyword("陌生字詞")
                .scoutCategory(fixture.category)
                .scoutReport("探索報告內容需超過二十個字以符合結構驗證")
                .scoutOpportunitySignals("[\"機會\"]")
                .scoutRiskSignals("[\"風險\"]")
                .scoutModel("test-model")
                .build();
        when(fixture.taskItems.findSourcingResultById(731L)).thenReturn(Optional.of(item));

        var response = fixture.service.latestResult(731L);

        assertAll(
                () -> assertNull(response.productId()),
                () -> assertEquals(10L, response.categoryId()),
                () -> assertEquals(List.of("機會"), response.opportunitySignals()),
                () -> assertEquals(List.of("風險"), response.riskSignals()),
                () -> assertTrue(response.canWatch()),
                () -> assertFalse(response.canPrioritize()));
        verifyNoInteractions(fixture.products);
    }

    @Test
    void watchRawResultMaterializesProductKeywordAndPendingCandidateOnce() {
        Fixture fixture = new Fixture();
        AiTaskItem item = fixture.completedRawItem();
        when(fixture.taskItems.findSourcingResultByIdForUpdate(731L))
                .thenReturn(Optional.of(item));
        when(fixture.leadTimes.findById(10L)).thenReturn(Optional.of(fixture.leadTime));
        when(fixture.keywords.findByKeyword("陌生字詞")).thenReturn(Optional.empty());
        TrendKeyword createdKeyword = TrendKeyword.builder()
                .id(41L).keyword("陌生字詞").enabled(true).build();
        when(fixture.keywords.save(any())).thenReturn(createdKeyword);
        when(fixture.products.findReusableSourcingProduct(41L, 10L)).thenReturn(Optional.empty());
        Product createdProduct = Product.builder()
                .id(701L).name("陌生字詞").category(fixture.category)
                .trackType(TrackType.B).status(ProductStatus.DRAFT)
                .sourcingStatus(SourcingStatus.PENDING)
                .keywords(new LinkedHashSet<>(Set.of(createdKeyword))).build();
        when(fixture.products.save(any())).thenReturn(createdProduct);
        when(fixture.candidates.findDetailedByProductId(701L)).thenReturn(Optional.empty());

        var response = fixture.service.watchResult(731L, "buyer@example.com");

        assertAll(
                () -> assertEquals(701L, response.productId()),
                () -> assertEquals(SourcingStatus.PENDING, response.sourcingStatus()),
                () -> assertEquals(ProductStatus.WATCHING, createdProduct.getStatus()),
                () -> assertEquals("探索報告內容需超過二十個字以符合結構驗證", response.report()),
                () -> assertTrue(response.canWatch()),
                () -> assertFalse(response.canPrioritize()),
                () -> assertEquals(createdProduct, item.getProduct()),
                () -> assertEquals(createdKeyword, item.getKeyword()));
        verify(fixture.candidates).save(argThat(candidate ->
                candidate.getProduct() == createdProduct
                        && candidate.getKeyword() == createdKeyword
                        && candidate.getLeadTimeDays() == 20));
        verify(fixture.taskItems).save(item);
        verify(fixture.events).publishEvent(new SourcingKeywordObservedEvent(41L));
    }

    @Test
    void watchRawResultReusesExistingRejectedCandidateAndRevivesIt() {
        Fixture fixture = new Fixture();
        AiTaskItem item = fixture.completedRawItem();
        fixture.candidate.getProduct().setSourcingStatus(SourcingStatus.REJECTED);
        fixture.keyword.setEnabled(false);
        fixture.candidate.setDrivingKeyword(fixture.keyword);
        fixture.candidate.setTimeGapDays(-3);
        when(fixture.taskItems.findSourcingResultByIdForUpdate(731L))
                .thenReturn(Optional.of(item));
        when(fixture.leadTimes.findById(10L)).thenReturn(Optional.of(fixture.leadTime));
        when(fixture.keywords.findByKeyword("陌生字詞")).thenReturn(Optional.of(fixture.keyword));
        when(fixture.products.findReusableSourcingProduct(31L, 10L))
                .thenReturn(Optional.of(fixture.candidate.getProduct()));

        var response = fixture.service.watchResult(731L, "buyer@example.com");

        assertAll(
                () -> assertEquals(SourcingStatus.PENDING, response.sourcingStatus()),
                () -> assertEquals(ProductStatus.WATCHING,
                        fixture.candidate.getProduct().getStatus()),
                () -> assertEquals(20, response.leadTimeDays()),
                () -> assertNull(response.timeGapDays()),
                () -> assertFalse(fixture.keyword.isEnabled()),
                () -> assertEquals(fixture.keyword, fixture.candidate.getDrivingKeyword()),
                () -> assertEquals(-3, fixture.candidate.getTimeGapDays()));
        verify(fixture.products, never()).save(any());
        verify(fixture.audits).save(any(AuditLog.class));
        verify(fixture.events, never()).publishEvent(any(SourcingKeywordObservedEvent.class));
    }

    private static SourcingScoutResult result() {
        return new SourcingScoutResult(
                new SourcingScoutOutput(
                        "探索報告內容需超過二十個字以符合結構驗證", List.of("機會"), List.of("風險")),
                false, "test-model", "scout-v6", 10, 5, 1);
    }

    private static final class Fixture {
        private final CategoryRepository categories = mock(CategoryRepository.class);
        private final CategoryLeadTimeRepository leadTimes = mock(CategoryLeadTimeRepository.class);
        private final TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        private final ProductRepository products = mock(ProductRepository.class);
        private final SourcingCandidateRepository candidates = mock(SourcingCandidateRepository.class);
        private final AiTaskItemRepository taskItems = mock(AiTaskItemRepository.class);
        private final HeatCompositeDailyRepository heatComposites = mock(HeatCompositeDailyRepository.class);
        private final AuditLogRepository audits = mock(AuditLogRepository.class);
        private final AppUserRepository users = mock(AppUserRepository.class);
        private final AiTaskService tasks = mock(AiTaskService.class);
        private final SourcingScoutAgent agent = mock(SourcingScoutAgent.class);
        private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        private final Category category = Category.builder().id(10L).name("零食").build();
        private final CategoryLeadTime leadTime = CategoryLeadTime.builder()
                .category(category).leadTimeDays(20).build();
        private final TrendKeyword keyword = TrendKeyword.builder().id(31L).keyword("低糖零食").build();
        private final SourcingCandidate candidate;
        private final SourcingScoutService service;

        private Fixture() {
            Product product = Product.builder()
                    .id(601L).name("低糖零食").category(category)
                    .trackType(TrackType.B).status(ProductStatus.DRAFT)
                    .sourcingStatus(SourcingStatus.PENDING)
                    .keywords(new LinkedHashSet<>(Set.of(keyword)))
                    .build();
            candidate = SourcingCandidate.builder()
                    .id(71L).product(product).keyword(keyword).category(category)
                    .leadTimeDays(20).build();
            when(candidates.findDetailedByProductId(601L)).thenReturn(Optional.of(candidate));
            SourcingPriorityCommandService priorityCommands =
                    new SourcingPriorityCommandService(
                            candidates, heatComposites, audits, users);
            service = new SourcingScoutService(
                    categories, leadTimes, keywords, products, candidates, taskItems,
                    priorityCommands, tasks,
                    new PromptSanitizer(), agent, new ObjectMapper(), events);
        }

        private AiTaskItem completedRawItem() {
            return AiTaskItem.builder()
                    .id(731L)
                    .task(AiTask.builder().id(730L).taskType(AiTaskType.SOURCING_SCOUT).build())
                    .scoutKeyword("陌生字詞")
                    .scoutCategory(category)
                    .scoutReport("探索報告內容需超過二十個字以符合結構驗證")
                    .scoutOpportunitySignals("[\"機會\"]")
                    .scoutRiskSignals("[\"風險\"]")
                    .scoutModel("test-model")
                    .scoutPromptVersion("scout-v6")
                    .build();
        }
    }
}
