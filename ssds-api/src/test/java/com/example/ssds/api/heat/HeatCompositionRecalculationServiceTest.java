package com.example.ssds.api.heat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.scoring.PureScoringBatchService;
import com.example.ssds.api.scoring.PureScoringBatchService.BatchResult;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.infra.service.HeatCompositeCalibrationService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** AC-14-5：權重變動後「先重算當日合成、再全量重評分」的順序與容錯。 */
class HeatCompositionRecalculationServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 29);
    private static final Instant NOW = Instant.parse("2026-09-29T02:00:00Z");

    private TrendKeywordRepository keywordRepository;
    private HeatCompositeCalibrationService calibrationService;
    private PureScoringBatchService scoringBatchService;
    private HeatCompositionRecalculationService service;

    @BeforeEach
    void setUp() {
        keywordRepository = mock(TrendKeywordRepository.class);
        calibrationService = mock(HeatCompositeCalibrationService.class);
        scoringBatchService = mock(PureScoringBatchService.class);
        service = new HeatCompositionRecalculationService(
                keywordRepository, calibrationService, scoringBatchService);
        when(scoringBatchService.evaluateAll(NOW))
                .thenReturn(new BatchResult(5, 4, 1, List.of(), List.of()));
    }

    private static TrendKeyword keyword(long id) {
        return TrendKeyword.builder().id(id).keyword("kw-" + id).build();
    }

    @Test
    @DisplayName("每個啟用關鍵字都重算當日合成，全部完成後才呼叫全量評分")
    void recomposesEveryEnabledKeywordThenScores() {
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(keyword(1), keyword(2)));
        when(calibrationService.computeAndPersist(anyLong(), any(LocalDate.class)))
                .thenReturn(Optional.of(mock(HeatCompositeDaily.class)));

        HeatCompositionRecalculationService.Result result = service.recomposeAndRescore(DATE, NOW);

        InOrder order = inOrder(calibrationService, scoringBatchService);
        order.verify(calibrationService).computeAndPersist(1L, DATE);
        order.verify(calibrationService).computeAndPersist(2L, DATE);
        order.verify(scoringBatchService).evaluateAll(NOW);
        assertThat(result.keywordsComposed()).isEqualTo(2);
        assertThat(result.keywordsSkipped()).isZero();
        assertThat(result.keywordsFailed()).isZero();
        assertThat(result.productsAttempted()).isEqualTo(5);
        assertThat(result.productsScored()).isEqualTo(4);
        assertThat(result.productsInsufficient()).isEqualTo(1);
    }

    @Test
    @DisplayName("當日無任何來源讀值的關鍵字略過（不視為熱度 0），評分照跑")
    void keywordWithoutReadingsIsSkipped() {
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(keyword(1), keyword(2)));
        when(calibrationService.computeAndPersist(1L, DATE)).thenReturn(Optional.empty());
        when(calibrationService.computeAndPersist(2L, DATE))
                .thenReturn(Optional.of(mock(HeatCompositeDaily.class)));

        HeatCompositionRecalculationService.Result result = service.recomposeAndRescore(DATE, NOW);

        assertThat(result.keywordsComposed()).isEqualTo(1);
        assertThat(result.keywordsSkipped()).isEqualTo(1);
        verify(scoringBatchService).evaluateAll(NOW);
    }

    @Test
    @DisplayName("單一關鍵字重算失敗不中斷整批，其餘關鍵字與評分照常執行")
    void oneKeywordFailureDoesNotAbortTheBatch() {
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of(keyword(1), keyword(2)));
        when(calibrationService.computeAndPersist(1L, DATE)).thenThrow(new IllegalStateException("boom"));
        when(calibrationService.computeAndPersist(2L, DATE))
                .thenReturn(Optional.of(mock(HeatCompositeDaily.class)));

        HeatCompositionRecalculationService.Result result = service.recomposeAndRescore(DATE, NOW);

        assertThat(result.keywordsFailed()).isEqualTo(1);
        assertThat(result.keywordsComposed()).isEqualTo(1);
        verify(calibrationService).computeAndPersist(2L, DATE);
        verify(scoringBatchService).evaluateAll(NOW);
    }

    @Test
    @DisplayName("沒有任何啟用關鍵字時不呼叫合成，仍執行評分")
    void noKeywordsStillScores() {
        when(keywordRepository.findByEnabledTrue()).thenReturn(List.of());

        HeatCompositionRecalculationService.Result result = service.recomposeAndRescore(DATE, NOW);

        verify(calibrationService, never()).computeAndPersist(anyLong(), any(LocalDate.class));
        verify(scoringBatchService).evaluateAll(NOW);
        assertThat(result.keywordsComposed()).isZero();
    }
}
