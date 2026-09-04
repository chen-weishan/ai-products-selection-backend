package com.example.ssds.api.sourcing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.agent.SourcingScoutAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.api.aitask.AiTaskService;
import com.example.ssds.api.aitask.dto.AiTaskResponse;
import com.example.ssds.api.sourcing.dto.SourcingScoutRequest;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SourcingScoutServiceTest {
    @Test
    void scoutOnlyStoresAgentSixReportAndDoesNotChangeTimeGapAuthorityFields() {
        Fixture fixture = new Fixture();
        fixture.candidate.setTimeGapDays(15);
        fixture.candidate.setDrivingKeyword(fixture.keyword);
        when(fixture.agent.scout(any(), eq(false))).thenReturn(result());
        when(fixture.heatComposites.findFirstByKeywordIdOrderByStatDateDesc(31L))
                .thenReturn(Optional.empty());

        var response = fixture.service.scout(601L, false);

        assertAll(
                () -> assertEquals("探索報告內容需超過二十個字以符合結構驗證", fixture.candidate.getScoutReport()),
                () -> assertEquals(15, fixture.candidate.getTimeGapDays()),
                () -> assertEquals(fixture.keyword, fixture.candidate.getDrivingKeyword()),
                () -> assertEquals(SourcingStatus.PENDING, fixture.candidate.getProduct().getSourcingStatus()),
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
        when(fixture.candidates.findByProductId(601L)).thenReturn(Optional.of(fixture.candidate));
        when(fixture.tasks.createSourcingScout(fixture.candidate.getProduct(), false))
                .thenReturn(mock(AiTaskResponse.class));

        fixture.service.start(new SourcingScoutRequest(" 低糖零食 ", 10L, false));

        verify(fixture.products, never()).save(any());
        verify(fixture.candidates, never()).save(any());
        verify(fixture.tasks).createSourcingScout(fixture.candidate.getProduct(), false);
    }

    @Test
    void startCreatesEnabledKeywordAndBTrackProductWithRelationInOneTransaction() {
        Fixture fixture = new Fixture();
        when(fixture.categories.findById(10L)).thenReturn(Optional.of(fixture.category));
        when(fixture.leadTimes.findById(10L)).thenReturn(Optional.of(fixture.leadTime));
        when(fixture.keywords.findByKeyword("新品關鍵字")).thenReturn(Optional.empty());
        when(fixture.keywords.save(any())).thenAnswer(invocation -> {
            TrendKeyword value = invocation.getArgument(0);
            value.setId(32L);
            return value;
        });
        when(fixture.products.findReusableSourcingProduct(32L, 10L)).thenReturn(Optional.empty());
        when(fixture.products.save(any())).thenAnswer(invocation -> {
            Product value = invocation.getArgument(0);
            value.setId(602L);
            return value;
        });
        when(fixture.candidates.findByProductId(602L)).thenReturn(Optional.empty());
        when(fixture.candidates.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.tasks.createSourcingScout(any(), eq(true))).thenReturn(mock(AiTaskResponse.class));

        fixture.service.start(new SourcingScoutRequest("新品關鍵字", 10L, true));

        ArgumentCaptor<TrendKeyword> keywordCaptor = ArgumentCaptor.forClass(TrendKeyword.class);
        verify(fixture.keywords).save(keywordCaptor.capture());
        assertTrue(keywordCaptor.getValue().isEnabled());
        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(fixture.products).save(productCaptor.capture());
        Product product = productCaptor.getValue();
        assertAll(
                () -> assertEquals("新品關鍵字", product.getName()),
                () -> assertEquals(TrackType.B, product.getTrackType()),
                () -> assertEquals(ProductStatus.DRAFT, product.getStatus()),
                () -> assertEquals(SourcingStatus.PENDING, product.getSourcingStatus()),
                () -> assertEquals(Set.of(keywordCaptor.getValue()), product.getKeywords()));
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
        private final HeatCompositeDailyRepository heatComposites = mock(HeatCompositeDailyRepository.class);
        private final AiTaskService tasks = mock(AiTaskService.class);
        private final SourcingScoutAgent agent = mock(SourcingScoutAgent.class);
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
            service = new SourcingScoutService(
                    categories, leadTimes, keywords, products, candidates, heatComposites, tasks,
                    new PromptSanitizer(), agent, new ObjectMapper());
        }
    }
}
