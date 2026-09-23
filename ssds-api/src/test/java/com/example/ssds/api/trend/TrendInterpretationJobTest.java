package com.example.ssds.api.trend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.model.trend.TrendInterpreterInput;
import com.example.ssds.ai.prompt.trend.TrendInterpreterPromptFactory;
import com.example.ssds.api.aitask.service.AiTaskService;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TrendInterpretationJobTest {
    @Test
    void enqueuesKeywordWithoutPreviousInterpretation() {
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        TrendInterpretationRepository interpretationRepository = mock(TrendInterpretationRepository.class);
        AiTaskService taskService = mock(AiTaskService.class);
        TrendKeyword keyword = TrendKeyword.builder().id(30L).keyword("新品關鍵字").build();
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(keyword));
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(30L))
                .thenReturn(Optional.of(HeatCompositeDaily.builder()
                        .keyword(keyword).stage(HeatStage.RISING)
                        .statDate(businessDate)
                        .slope30d(new BigDecimal("0.20")).build()));
        when(interpretationRepository.findByKeywordIdAndCurrentTrue(30L))
                .thenReturn(Optional.empty());

        new TrendInterpretationJob(keywordRepository, compositeRepository,
                interpretationRepository, taskService, new ObjectMapper())
                .enqueueSignificantKeywords(businessDate);

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
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        HeatCompositeDaily latest = HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(businessDate)
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
                .promptVersion(TrendInterpreterPromptFactory.PROMPT_VERSION)
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

        job.enqueueSignificantKeywords(businessDate);

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
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        HeatCompositeDaily latest = HeatCompositeDaily.builder()
                .keyword(keyword).stage(HeatStage.PLATEAU)
                .statDate(businessDate)
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
                .promptVersion(TrendInterpreterPromptFactory.PROMPT_VERSION)
                .inputSnapshot(mapper.writeValueAsString(previousInput))
                .build();
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(keyword));
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(32L))
                .thenReturn(Optional.of(latest));
        when(interpretationRepository.findByKeywordIdAndCurrentTrue(32L))
                .thenReturn(Optional.of(previous));

        new TrendInterpretationJob(keywordRepository, compositeRepository,
                interpretationRepository, taskService, mapper)
                .enqueueSignificantKeywords(businessDate);

        verifyNoInteractions(taskService);
    }

    @Test
    void doesNotEnqueueWhenLatestCompositeIsFromPreviousDay() {
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        TrendInterpretationRepository interpretationRepository = mock(TrendInterpretationRepository.class);
        AiTaskService taskService = mock(AiTaskService.class);
        TrendKeyword keyword = TrendKeyword.builder().id(33L).keyword("昨日關鍵字").build();
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(keyword));
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(33L))
                .thenReturn(Optional.of(HeatCompositeDaily.builder()
                        .keyword(keyword)
                        .statDate(businessDate.minusDays(1))
                        .stage(HeatStage.RISING)
                        .slope30d(new BigDecimal("0.20"))
                        .build()));

        new TrendInterpretationJob(keywordRepository, compositeRepository,
                interpretationRepository, taskService, new ObjectMapper())
                .enqueueSignificantKeywords(businessDate);

        verifyNoInteractions(taskService);
        verifyNoInteractions(interpretationRepository);
    }

    @Test
    void oneKeywordFailureDoesNotBlockEnqueueForRemainingKeywords() {
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        TrendInterpretationRepository interpretationRepository = mock(TrendInterpretationRepository.class);
        AiTaskService taskService = mock(AiTaskService.class);
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        TrendKeyword failed = TrendKeyword.builder().id(34L).keyword("壞資料").build();
        TrendKeyword succeeded = TrendKeyword.builder().id(35L).keyword("可分析資料").build();
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(failed, succeeded));
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(34L))
                .thenThrow(new IllegalStateException("isolated failure"));
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(35L))
                .thenReturn(Optional.of(HeatCompositeDaily.builder()
                        .keyword(succeeded)
                        .statDate(businessDate)
                        .stage(HeatStage.RISING)
                        .slope30d(new BigDecimal("0.20"))
                        .build()));
        when(interpretationRepository.findByKeywordIdAndCurrentTrue(35L))
                .thenReturn(Optional.empty());

        new TrendInterpretationJob(
                keywordRepository,
                compositeRepository,
                interpretationRepository,
                taskService,
                new ObjectMapper())
                .enqueueSignificantKeywords(businessDate);

        verify(taskService).createScheduledTrendInterpretation(List.of(35L));
    }

    @Test
    void enqueuesWhenPromptVersionChanges() {
        Fixture fixture = fixtureWithPrevious(36L, HeatStage.PLATEAU, "trend-v3", "0.20", "0.20");

        fixture.job().enqueueSignificantKeywords(fixture.businessDate());

        verify(fixture.taskService()).createScheduledTrendInterpretation(List.of(36L));
    }

    @Test
    void enqueuesWhenStageChanges() {
        Fixture fixture = fixtureWithPrevious(
                37L,
                HeatStage.RISING,
                TrendInterpreterPromptFactory.PROMPT_VERSION,
                "0.20",
                "0.20");
        fixture.latest().setStage(HeatStage.DECLINING);

        fixture.job().enqueueSignificantKeywords(fixture.businessDate());

        verify(fixture.taskService()).createScheduledTrendInterpretation(List.of(37L));
    }

    @Test
    void chunksSignificantKeywordsAtOneHundredPerTask() {
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        TrendInterpretationRepository interpretationRepository = mock(TrendInterpretationRepository.class);
        AiTaskService taskService = mock(AiTaskService.class);
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        List<TrendKeyword> keywords = java.util.stream.LongStream.rangeClosed(1, 201)
                .mapToObj(id -> TrendKeyword.builder().id(id).keyword("keyword-" + id).build())
                .toList();
        when(keywordRepository.findByEnabledTrue()).thenReturn(keywords);
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(anyLong()))
                .thenAnswer(invocation -> Optional.of(HeatCompositeDaily.builder()
                        .keyword(TrendKeyword.builder().id(invocation.getArgument(0)).build())
                        .statDate(businessDate)
                        .stage(HeatStage.RISING)
                        .slope30d(new BigDecimal("0.20"))
                        .build()));
        when(interpretationRepository.findByKeywordIdAndCurrentTrue(anyLong()))
                .thenReturn(Optional.empty());

        new TrendInterpretationJob(
                keywordRepository,
                compositeRepository,
                interpretationRepository,
                taskService,
                new ObjectMapper())
                .enqueueSignificantKeywords(businessDate);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> chunks = ArgumentCaptor.forClass(List.class);
        verify(taskService, times(3)).createScheduledTrendInterpretation(chunks.capture());
        assertEquals(List.of(100, 100, 1), chunks.getAllValues().stream().map(List::size).toList());
    }

    private static Fixture fixtureWithPrevious(
            long keywordId,
            HeatStage previousStage,
            String promptVersion,
            String previousSlope,
            String latestSlope) {
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository compositeRepository = mock(HeatCompositeDailyRepository.class);
        TrendInterpretationRepository interpretationRepository = mock(TrendInterpretationRepository.class);
        AiTaskService taskService = mock(AiTaskService.class);
        ObjectMapper mapper = new ObjectMapper();
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        TrendKeyword keyword = TrendKeyword.builder().id(keywordId).keyword("變動關鍵字").build();
        HeatCompositeDaily latest = HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(businessDate)
                .stage(previousStage)
                .slope30d(new BigDecimal(latestSlope))
                .build();
        TrendInterpreterInput previousInput = new TrendInterpreterInput(
                keywordId,
                List.of(new TrendInterpreterInput.CompositePoint(
                        "2026-09-21",
                        new BigDecimal("50"),
                        BigDecimal.ZERO,
                        new BigDecimal(previousSlope))),
                List.of(),
                List.of());
        TrendInterpretation previous = TrendInterpretation.builder()
                .heatStage(previousStage)
                .promptVersion(promptVersion)
                .inputSnapshot(write(mapper, previousInput))
                .build();
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(keyword));
        when(compositeRepository.findFirstByKeywordIdOrderByStatDateDesc(keywordId))
                .thenReturn(Optional.of(latest));
        when(interpretationRepository.findByKeywordIdAndCurrentTrue(keywordId))
                .thenReturn(Optional.of(previous));
        return new Fixture(
                new TrendInterpretationJob(
                        keywordRepository,
                        compositeRepository,
                        interpretationRepository,
                        taskService,
                        mapper),
                taskService,
                latest,
                businessDate);
    }

    private static String write(ObjectMapper mapper, TrendInterpreterInput input) {
        try {
            return mapper.writeValueAsString(input);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record Fixture(
            TrendInterpretationJob job,
            AiTaskService taskService,
            HeatCompositeDaily latest,
            LocalDate businessDate) {}
}
