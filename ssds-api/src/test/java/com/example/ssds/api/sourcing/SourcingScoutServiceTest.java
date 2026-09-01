package com.example.ssds.api.sourcing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.agent.SourcingScoutAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.api.aitask.AiTaskService;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourcingScoutServiceTest {
    @Test
    void latestHeatCompositeSuppliesTheWholeStageSnapshot() {
        Fixture fixture = new Fixture();
        TrendInterpretation trend = TrendInterpretation.builder()
                .id(91L)
                .keyword(fixture.keyword)
                .heatStage(HeatStage.RISING)
                .stageWeeks((short) 3)
                .estimatedLifespanDays(56)
                .current(true)
                .build();
        HeatCompositeDaily composite = HeatCompositeDaily.builder()
                .keyword(fixture.keyword)
                .statDate(java.time.LocalDate.of(2026, 8, 23))
                .stage(HeatStage.PLATEAU)
                .stageWeeks((short) 4)
                .estimatedLifespanDays(35)
                .build();
        when(fixture.heatComposites.findFirstByKeywordIdOrderByStatDateDesc(31L))
                .thenReturn(Optional.of(composite));
        when(fixture.trends.findByKeywordIdAndCurrentTrue(31L)).thenReturn(Optional.of(trend));
        when(fixture.agent.scout(any(), eq(false))).thenReturn(result(HeatStage.DECLINING));

        var response = fixture.service.scout(601L, false);

        assertAll(
                () -> assertEquals(HeatStage.PLATEAU, fixture.candidate.getHeatStage()),
                () -> assertEquals((short) 4, fixture.candidate.getStageWeeks()),
                () -> assertEquals(35, fixture.candidate.getEstimatedLifespanDays()),
                () -> assertEquals(15, fixture.candidate.getTimeGapDays()),
                () -> assertNull(fixture.candidate.getTrendInterpretation()),
                () -> assertEquals(HeatStage.PLATEAU, response.heatStage()));
        verify(fixture.candidates).save(fixture.candidate);
    }

    @Test
    void agentStageAndRuleValuesAreUsedTogetherWhenNoCurrentTrendExists() {
        Fixture fixture = new Fixture();
        fixture.candidate.setTrendInterpretation(TrendInterpretation.builder().id(90L).build());
        when(fixture.heatComposites.findFirstByKeywordIdOrderByStatDateDesc(31L))
                .thenReturn(Optional.empty());
        when(fixture.trends.findByKeywordIdAndCurrentTrue(31L)).thenReturn(Optional.empty());
        when(fixture.agent.scout(any(), eq(true))).thenReturn(result(HeatStage.PLATEAU));

        fixture.service.scout(601L, true);

        assertAll(
                () -> assertEquals(HeatStage.PLATEAU, fixture.candidate.getHeatStage()),
                () -> assertEquals((short) 1, fixture.candidate.getStageWeeks()),
                () -> assertEquals(42, fixture.candidate.getEstimatedLifespanDays()),
                () -> assertEquals(22, fixture.candidate.getTimeGapDays()),
                () -> assertNull(fixture.candidate.getTrendInterpretation()));
    }

    private static SourcingScoutResult result(HeatStage stage) {
        return new SourcingScoutResult(
                new SourcingScoutOutput("探索報告內容", List.of("機會"), List.of("風險"), stage),
                false, "test-model", "scout-v5", 10, 5, 1);
    }

    private static final class Fixture {
        private final CategoryRepository categories = mock(CategoryRepository.class);
        private final CategoryLeadTimeRepository leadTimes = mock(CategoryLeadTimeRepository.class);
        private final TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        private final ProductRepository products = mock(ProductRepository.class);
        private final SourcingCandidateRepository candidates = mock(SourcingCandidateRepository.class);
        private final TrendInterpretationRepository trends = mock(TrendInterpretationRepository.class);
        private final HeatCompositeDailyRepository heatComposites = mock(HeatCompositeDailyRepository.class);
        private final AiTaskService tasks = mock(AiTaskService.class);
        private final SourcingScoutAgent agent = mock(SourcingScoutAgent.class);
        private final TrendKeyword keyword = TrendKeyword.builder().id(31L).keyword("低糖零食").build();
        private final SourcingCandidate candidate;
        private final SourcingScoutService service;

        private Fixture() {
            Category category = Category.builder().id(10L).name("零食").build();
            Product product = Product.builder()
                    .id(601L)
                    .name("低糖零食")
                    .category(category)
                    .trackType(TrackType.B)
                    .status(ProductStatus.DRAFT)
                    .sourcingStatus(SourcingStatus.PENDING)
                    .build();
            candidate = SourcingCandidate.builder()
                    .id(71L)
                    .product(product)
                    .keyword(keyword)
                    .category(category)
                    .leadTimeDays(20)
                    .build();
            when(candidates.findDetailedByProductId(601L)).thenReturn(Optional.of(candidate));
            ObjectMapper mapper = new ObjectMapper();
            service = new SourcingScoutService(
                    categories, leadTimes, keywords, products, candidates, trends, heatComposites, tasks,
                    new PromptSanitizer(), agent, mapper);
        }
    }
}
