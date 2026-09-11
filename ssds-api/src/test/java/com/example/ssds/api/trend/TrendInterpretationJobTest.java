package com.example.ssds.api.trend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.model.TrendInterpreterInput;
import com.example.ssds.api.aitask.AiTaskService;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class TrendInterpretationJobTest {
    @Test
    void enqueuesKeywordWithoutPreviousInterpretation() {
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        TrendInterpretationRepository interpretationRepository = mock(TrendInterpretationRepository.class);
        AiTaskService taskService = mock(AiTaskService.class);
        TrendKeyword keyword = TrendKeyword.builder().id(30L).keyword("新品關鍵字").build();
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(keyword));
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(30L))
                .thenReturn(Optional.of(HeatCompositeDaily.builder()
                        .keyword(keyword).stage(HeatStage.RISING)
                        .slope30d(new BigDecimal("0.20")).build()));
        when(interpretationRepository.findByKeywordIdAndCurrentTrue(30L))
                .thenReturn(Optional.empty());

        new TrendInterpretationJob(keywordRepository, compositeRepository,
                interpretationRepository, taskService, new ObjectMapper())
                .enqueueSignificantKeywords();

        verify(taskService).createScheduledTrendInterpretation(List.of(30L));
    }

    @Test
    void enqueuesOnlyKeywordCrossingSlopeBucket() throws Exception {
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        TrendInterpretationRepository interpretationRepository =
                mock(TrendInterpretationRepository.class);
        AiTaskService taskService = mock(AiTaskService.class);
        ObjectMapper mapper = new ObjectMapper();
        TrendKeyword keyword = TrendKeyword.builder().id(31L).keyword("抹茶").build();
        HeatCompositeDaily latest = HeatCompositeDaily.builder()
                .keyword(keyword)
                .stage(HeatStage.RISING)
                .slope30d(new BigDecimal("0.41"))
                .build();
        TrendInterpreterInput previousInput = new TrendInterpreterInput(
                31L,
                List.of(new TrendInterpreterInput.CompositePoint(
                        "2026-08-25", new BigDecimal("70"),
                        new BigDecimal("0.20"), new BigDecimal("0.30"))),
                List.of(),
                List.of(new TrendInterpreterInput.AllowedOutput(HeatStage.RISING, 4, 56)));
        TrendInterpretation previous = TrendInterpretation.builder()
                .heatStage(HeatStage.RISING)
                .promptVersion("trend-v2")
                .inputSnapshot(mapper.writeValueAsString(previousInput))
                .build();
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(keyword));
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(31L))
                .thenReturn(Optional.of(latest));
        when(interpretationRepository.findByKeywordIdAndCurrentTrue(31L))
                .thenReturn(Optional.of(previous));
        TrendInterpretationJob job = new TrendInterpretationJob(
                keywordRepository, compositeRepository, interpretationRepository,
                taskService, mapper);

        job.enqueueSignificantKeywords();

        verify(taskService).createScheduledTrendInterpretation(List.of(31L));
    }

    @Test
    void doesNotEnqueueWhenStagePromptAndSlopeBucketAreUnchanged() throws Exception {
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        TrendInterpretationRepository interpretationRepository = mock(TrendInterpretationRepository.class);
        AiTaskService taskService = mock(AiTaskService.class);
        ObjectMapper mapper = new ObjectMapper();
        TrendKeyword keyword = TrendKeyword.builder().id(32L).keyword("穩定關鍵字").build();
        HeatCompositeDaily latest = HeatCompositeDaily.builder()
                .keyword(keyword).stage(HeatStage.PLATEAU)
                .slope30d(new BigDecimal("0.34")).build();
        TrendInterpreterInput previousInput = new TrendInterpreterInput(
                32L,
                List.of(new TrendInterpreterInput.CompositePoint(
                        "2026-08-25", new BigDecimal("70"),
                        new BigDecimal("0.20"), new BigDecimal("0.31"))),
                List.of(),
                List.of(new TrendInterpreterInput.AllowedOutput(HeatStage.PLATEAU, 2, 42)));
        TrendInterpretation previous = TrendInterpretation.builder()
                .heatStage(HeatStage.PLATEAU)
                .promptVersion("trend-v2")
                .inputSnapshot(mapper.writeValueAsString(previousInput))
                .build();
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(keyword));
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(32L))
                .thenReturn(Optional.of(latest));
        when(interpretationRepository.findByKeywordIdAndCurrentTrue(32L))
                .thenReturn(Optional.of(previous));

        new TrendInterpretationJob(keywordRepository, compositeRepository,
                interpretationRepository, taskService, mapper).enqueueSignificantKeywords();

        verifyNoInteractions(taskService);
    }
}
