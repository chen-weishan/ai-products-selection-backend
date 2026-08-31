package com.example.ssds.api.trend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.agent.TrendInterpreterAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.ai.prompt.TrendInterpreterPromptFactory;
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
        TrendKeyword keyword = TrendKeyword.builder().id(31L).keyword("抹茶").build();
        LocalDate latestDate = LocalDate.of(2026, 8, 26);
        List<HeatCompositeDaily> composites = new ArrayList<>();
        for (int offset = 21; offset >= 0; offset--) {
            composites.add(HeatCompositeDaily.builder()
                    .keyword(keyword)
                    .statDate(latestDate.minusDays(offset))
                    .compositeValue(BigDecimal.valueOf(40 + (21 - offset)))
                    .slope7d(new BigDecimal("0.10"))
                    .slope30d(new BigDecimal("0.30"))
                    .stage(HeatStage.RISING)
                    .stageWeeks((short) 3)
                    .appliedWeights("{}")
                    .build());
        }
        HeatCompositeDaily latest = composites.getLast();
        HeatSource source = HeatSource.builder()
                .id(1L)
                .sourceCode(HeatSourceCode.THREADS)
                .availability(SourceAvailability.AVAILABLE)
                .build();
        List<HeatReading> readings = List.of(
                reading(keyword, source, latestDate.minusDays(30), "40"),
                reading(keyword, source, latestDate.minusDays(7), "60"),
                reading(keyword, source, latestDate, "70"));
        TrendInterpreterOutput output = new TrendInterpreterOutput(HeatStage.RISING, 4, 56);
        TrendInterpreterResult result = new TrendInterpreterResult(
                output, false, null, false, "mistral-small-latest",
                TrendInterpreterPromptFactory.PROMPT_VERSION, 100, 20, 1);
        when(keywordRepository.findById(31L)).thenReturn(Optional.of(keyword));
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(31L))
                .thenReturn(Optional.of(latest));
        when(compositeRepository.findByKeywordIdAndStatDateBetweenOrderByStatDateAsc(
                eq(31L), eq(latestDate.minusDays(89)), eq(latestDate)))
                .thenReturn(composites);
        when(readingRepository.findByKeywordIdAndReadingDateBetweenOrderByReadingDateAsc(
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
                new ObjectMapper().findAndRegisterModules());

        var response = service.interpret(31L, false);

        ArgumentCaptor<TrendInterpreterInput> inputCaptor =
                ArgumentCaptor.forClass(TrendInterpreterInput.class);
        verify(agent).interpret(inputCaptor.capture(), eq(false));
        TrendInterpreterInput input = inputCaptor.getValue();
        assertEquals(22, input.compositeSeries().size());
        assertEquals(1, input.sourceTrends().size());
        assertEquals(SourceAvailability.AVAILABLE, input.sourceTrends().getFirst().availability());
        assertTrue(input.allowedOutputs().contains(
                new TrendInterpreterInput.AllowedOutput(HeatStage.RISING, 4, 56)));
        assertEquals(56, latest.getEstimatedLifespanDays());

        ArgumentCaptor<TrendInterpretation> historyCaptor =
                ArgumentCaptor.forClass(TrendInterpretation.class);
        verify(interpretationRepository).save(historyCaptor.capture());
        assertEquals("trend-v1", historyCaptor.getValue().getPromptVersion());
        assertTrue(historyCaptor.getValue().getInputSnapshot().contains("compositeSeries"));
        assertEquals("MODEL_NUMERIC", response.modelAlias());
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
}
