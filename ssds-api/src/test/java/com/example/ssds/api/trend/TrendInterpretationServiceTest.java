package com.example.ssds.api.trend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.agent.TrendInterpreterAgent;
import com.example.ssds.ai.model.FallbackReason;
import com.example.ssds.ai.model.trend.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.ai.prompt.trend.TrendInterpreterPromptFactory;
import com.example.ssds.api.sourcing.SourcingTimeGapRecalculationService;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TrendInterpretationServiceTest {
    @Test
    void buildsKeywordInputUpdatesLatestCompositeAndPersistsHistory() {
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        HeatReadingRepository readingRepository = mock(HeatReadingRepository.class);
        TrendInterpretationRepository interpretationRepository =
                mock(TrendInterpretationRepository.class);
        TrendInterpreterAgent agent = mock(TrendInterpreterAgent.class);
        SourcingTimeGapRecalculationService timeGapService =
                mock(SourcingTimeGapRecalculationService.class);
        TrendKeyword keyword = TrendKeyword.builder().id(31L).keyword("抹茶").build();
        LocalDate latestDate = LocalDate.of(2026, 8, 26);
        List<HeatCompositeDaily> composites = new ArrayList<>();
        for (int offset = 89; offset >= 0; offset--) {
            composites.add(HeatCompositeDaily.builder()
                    .keyword(keyword)
                    .statDate(latestDate.minusDays(offset))
                    .compositeValue(BigDecimal.valueOf(40 + (89 - offset)))
                    .slope7d(new BigDecimal("0.10"))
                    .slope30d(new BigDecimal("0.30"))
                    .stage(HeatStage.RISING)
                    .stageWeeks((short) 13)
                    .stageSource(HeatValueSource.RULE)
                    .lifespanSource(HeatValueSource.RULE)
                    .appliedWeights("{}")
                    .build());
        }
        HeatCompositeDaily latest = composites.getLast();
        HeatSource source = HeatSource.builder()
                .id(1L)
                .sourceCode(HeatSourceCode.THREADS)
                .granularity(HeatGranularity.KEYWORD)
                .availability(SourceAvailability.AVAILABLE)
                .build();
        Category category = Category.builder().id(10L).name("進口食品").build();
        HeatSource categorySource = HeatSource.builder()
                .id(2L)
                .sourceCode(HeatSourceCode.INSTAGRAM)
                .granularity(HeatGranularity.CATEGORY)
                .availability(SourceAvailability.AVAILABLE)
                .build();
        List<HeatReading> readings = List.of(
                reading(keyword, source, latestDate.minusDays(30), "40"),
                reading(keyword, source, latestDate.minusDays(7), "60"),
                reading(keyword, source, latestDate, "70"),
                categoryReading(category, categorySource, latestDate.minusDays(7), "50"),
                categoryReading(category, categorySource, latestDate, "65"));
        TrendInterpreterOutput output = new TrendInterpreterOutput(HeatStage.RISING, 13, 56);
        TrendInterpreterResult result = new TrendInterpreterResult(
                output, false, null, false, "mistral-small-latest",
                TrendInterpreterPromptFactory.PROMPT_VERSION, 100, 20, 1);
        when(keywordRepository.findById(31L)).thenReturn(Optional.of(keyword));
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(31L))
                .thenReturn(Optional.of(latest));
        when(compositeRepository.findByKeywordIdAndStatDateBetweenOrderByStatDateAsc(
                eq(31L), eq(latestDate.minusDays(89)), eq(latestDate)))
                .thenReturn(composites);
        when(readingRepository.findForKeywordIncludingCategorySources(
                eq(31L), eq(latestDate.minusDays(89)), eq(latestDate)))
                .thenReturn(readings);
        when(agent.interpret(any(), eq(false))).thenReturn(result);
        TrendInterpretationService service = new TrendInterpretationService(
                keywordRepository,
                compositeRepository,
                readingRepository,
                interpretationRepository,
                new PromptSanitizer(),
                agent,
                new ObjectMapper().findAndRegisterModules(),
                timeGapService);

        var response = service.interpret(31L, false);

        ArgumentCaptor<TrendInterpreterInput> inputCaptor =
                ArgumentCaptor.forClass(TrendInterpreterInput.class);
        verify(agent).interpret(inputCaptor.capture(), eq(false));
        TrendInterpreterInput input = inputCaptor.getValue();
        assertEquals(90, input.compositeSeries().size());
        assertEquals(latestDate.minusDays(89).toString(), input.compositeSeries().getFirst().date());
        assertEquals(latestDate.toString(), input.compositeSeries().getLast().date());
        assertEquals(2, input.sourceTrends().size());
        assertEquals(SourceAvailability.AVAILABLE, input.sourceTrends().getFirst().availability());
        TrendInterpreterInput.SourceTrend instagram = input.sourceTrends().stream()
                .filter(value -> value.source() == HeatSourceCode.INSTAGRAM)
                .findFirst()
                .orElseThrow();
        assertEquals(HeatGranularity.CATEGORY, instagram.granularity());
        assertEquals(10L, instagram.categoryId());
        assertTrue(input.allowedOutputs().contains(
                new TrendInterpreterInput.AllowedOutput(HeatStage.RISING, 13, 56)));
        assertEquals(56, latest.getEstimatedLifespanDays());
        assertEquals(HeatValueSource.AGENT, latest.getStageSource());
        assertEquals(HeatValueSource.AGENT, latest.getLifespanSource());

        ArgumentCaptor<TrendInterpretation> historyCaptor =
                ArgumentCaptor.forClass(TrendInterpretation.class);
        verify(interpretationRepository).save(historyCaptor.capture());
        assertEquals("trend-v4", historyCaptor.getValue().getPromptVersion());
        assertTrue(historyCaptor.getValue().getInputSnapshot().contains("compositeSeries"));
        assertEquals("MODEL_NUMERIC", response.modelAlias());
        verify(timeGapService).recalculateAffectedByKeyword(31L);
        verify(timeGapService, never()).recalculateAll();
    }

    @Test
    void marksRuleSourcesWhenAgentFallsBack() {
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        HeatReadingRepository readingRepository = mock(HeatReadingRepository.class);
        TrendInterpretationRepository interpretationRepository =
                mock(TrendInterpretationRepository.class);
        TrendInterpreterAgent agent = mock(TrendInterpreterAgent.class);
        SourcingTimeGapRecalculationService timeGapService =
                mock(SourcingTimeGapRecalculationService.class);
        TrendKeyword keyword = TrendKeyword.builder().id(32L).keyword("可可").build();
        LocalDate latestDate = LocalDate.of(2026, 9, 22);
        HeatCompositeDaily latest = HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(latestDate)
                .compositeValue(new BigDecimal("50"))
                .slope7d(new BigDecimal("0.20"))
                .slope30d(BigDecimal.ZERO)
                .stage(HeatStage.PLATEAU)
                .stageWeeks((short) 1)
                .estimatedLifespanDays(42)
                .stageSource(HeatValueSource.RULE)
                .lifespanSource(HeatValueSource.RULE)
                .appliedWeights("{}")
                .build();
        TrendInterpreterResult fallback = new TrendInterpreterResult(
                new TrendInterpreterOutput(HeatStage.PLATEAU, 1, 42),
                true,
                FallbackReason.AI_UNAVAILABLE,
                false,
                "rule-fallback",
                TrendInterpreterPromptFactory.PROMPT_VERSION,
                null,
                null,
                1);
        when(keywordRepository.findById(32L)).thenReturn(Optional.of(keyword));
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(32L))
                .thenReturn(Optional.of(latest));
        when(compositeRepository.findByKeywordIdAndStatDateBetweenOrderByStatDateAsc(
                eq(32L), eq(latestDate.minusDays(89)), eq(latestDate)))
                .thenReturn(List.of(latest));
        when(readingRepository.findForKeywordIncludingCategorySources(
                eq(32L), eq(latestDate.minusDays(89)), eq(latestDate)))
                .thenReturn(List.of());
        when(agent.interpret(any(), eq(false))).thenReturn(fallback);
        TrendInterpretationService service = new TrendInterpretationService(
                keywordRepository,
                compositeRepository,
                readingRepository,
                interpretationRepository,
                new PromptSanitizer(),
                agent,
                new ObjectMapper().findAndRegisterModules(),
                timeGapService);

        service.interpret(32L, false);

        assertEquals(HeatValueSource.RULE, latest.getStageSource());
        assertEquals(HeatValueSource.RULE, latest.getLifespanSource());
        verify(compositeRepository).save(latest);
        verify(timeGapService).recalculateAffectedByKeyword(32L);
    }

    @Test
    void allowedOutputsRestartStageWeeksWhenCalendarHistoryHasGap() {
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        HeatReadingRepository readingRepository = mock(HeatReadingRepository.class);
        TrendInterpretationRepository interpretationRepository =
                mock(TrendInterpretationRepository.class);
        TrendInterpreterAgent agent = mock(TrendInterpreterAgent.class);
        SourcingTimeGapRecalculationService timeGapService =
                mock(SourcingTimeGapRecalculationService.class);
        TrendKeyword keyword = TrendKeyword.builder().id(33L).keyword("缺日曲線").build();
        LocalDate latestDate = LocalDate.of(2026, 9, 22);
        List<HeatCompositeDaily> composites = new ArrayList<>();
        for (int day = 1; day <= 14; day++) {
            composites.add(HeatCompositeDaily.builder()
                    .keyword(keyword)
                    .statDate(LocalDate.of(2026, 9, day))
                    .compositeValue(new BigDecimal("40"))
                    .stage(HeatStage.PLATEAU)
                    .build());
        }
        HeatCompositeDaily latest = HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(latestDate)
                .compositeValue(new BigDecimal("50"))
                .slope30d(BigDecimal.ZERO)
                .stage(HeatStage.PLATEAU)
                .stageWeeks((short) 1)
                .estimatedLifespanDays(42)
                .stageSource(HeatValueSource.RULE)
                .lifespanSource(HeatValueSource.RULE)
                .appliedWeights("{}")
                .build();
        composites.add(latest);
        TrendInterpreterResult fallback = new TrendInterpreterResult(
                new TrendInterpreterOutput(HeatStage.PLATEAU, 1, 42),
                true,
                FallbackReason.DATA_INSUFFICIENT,
                false,
                "rule-fallback",
                TrendInterpreterPromptFactory.PROMPT_VERSION,
                null,
                null,
                0);
        when(keywordRepository.findById(33L)).thenReturn(Optional.of(keyword));
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(33L))
                .thenReturn(Optional.of(latest));
        when(compositeRepository.findByKeywordIdAndStatDateBetweenOrderByStatDateAsc(
                eq(33L), eq(latestDate.minusDays(89)), eq(latestDate)))
                .thenReturn(composites);
        when(readingRepository.findForKeywordIncludingCategorySources(
                eq(33L), eq(latestDate.minusDays(89)), eq(latestDate)))
                .thenReturn(List.of());
        when(agent.interpret(any(), eq(false))).thenReturn(fallback);
        TrendInterpretationService service = new TrendInterpretationService(
                keywordRepository,
                compositeRepository,
                readingRepository,
                interpretationRepository,
                new PromptSanitizer(),
                agent,
                new ObjectMapper().findAndRegisterModules(),
                timeGapService);

        service.interpret(33L, false);

        ArgumentCaptor<TrendInterpreterInput> inputCaptor =
                ArgumentCaptor.forClass(TrendInterpreterInput.class);
        verify(agent).interpret(inputCaptor.capture(), eq(false));
        assertTrue(inputCaptor.getValue().allowedOutputs().contains(
                new TrendInterpreterInput.AllowedOutput(HeatStage.PLATEAU, 1, 42)));
        assertFalse(inputCaptor.getValue().allowedOutputs().contains(
                new TrendInterpreterInput.AllowedOutput(HeatStage.PLATEAU, 4, 35)));
    }

    private static HeatReading reading(
            TrendKeyword keyword, HeatSource source, LocalDate date, String percentile) {
        return HeatReading.builder()
                .keyword(keyword)
                .source(source)
                .readingDate(date)
                .rawValue(BigDecimal.ONE)
                .percentileWithinSource(new BigDecimal(percentile))
                .build();
    }

    private static HeatReading categoryReading(
            Category category, HeatSource source, LocalDate date, String percentile) {
        return HeatReading.builder()
                .category(category)
                .source(source)
                .readingDate(date)
                .rawValue(BigDecimal.ONE)
                .percentileWithinSource(new BigDecimal(percentile))
                .build();
    }
}
