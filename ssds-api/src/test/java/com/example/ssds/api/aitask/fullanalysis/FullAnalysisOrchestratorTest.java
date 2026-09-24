package com.example.ssds.api.aitask.fullanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.example.ssds.api.insight.ProductInsightService;
import com.example.ssds.api.insight.dto.ProductInsightResponse;
import com.example.ssds.api.recommendation.RecommendationService;
import com.example.ssds.api.recommendation.dto.RecommendationResponse;
import com.example.ssds.api.review.ReviewRiskService;
import com.example.ssds.api.review.dto.ReviewRiskResponse;
import com.example.ssds.api.scene.SceneClassificationService;
import com.example.ssds.api.scene.dto.SceneClassificationResponse;
import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationResult;
import com.example.ssds.api.scoring.ScoreExecutionService;
import com.example.ssds.api.scoring.ScoreExecutionService.EvaluationCommand;
import com.example.ssds.api.scoring.factor.ScoringFactorBatchService.PreparedPopulation;
import com.example.ssds.ai.model.scene.SceneCode;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.SceneType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class FullAnalysisOrchestratorTest {
    @Test
    void runsPreScoringAgentsBeforeScoreDependentAgents() {
        SceneClassificationService scene = mock(SceneClassificationService.class);
        ReviewRiskService review = mock(ReviewRiskService.class);
        ProductInsightService insight = mock(ProductInsightService.class);
        RecommendationService recommendation = mock(RecommendationService.class);
        ScoreExecutionService scoring = mock(ScoreExecutionService.class);
        PreparedPopulation factorPopulation = mock(PreparedPopulation.class);
        ReviewRiskResponse reviewResponse = mock(ReviewRiskResponse.class);
        SceneClassificationResponse sceneResponse = mock(SceneClassificationResponse.class);
        ProductInsightResponse insightResponse = mock(ProductInsightResponse.class);
        RecommendationResponse recommendationResponse = mock(RecommendationResponse.class);
        when(review.analyze(101L, false)).thenReturn(reviewResponse);
        when(scene.classify(101L, false)).thenReturn(sceneResponse);
        stubScene(sceneResponse);
        when(scoring.evaluate(any(), same(factorPopulation))).thenReturn(scored(701L));
        when(insight.analyze(101L, 701L, false)).thenReturn(insightResponse);
        when(recommendation.recommend(101L, 701L, false)).thenReturn(recommendationResponse);
        when(reviewResponse.analysisCompleted()).thenReturn(true);
        when(insightResponse.analysisCompleted()).thenReturn(true);

        FullAnalysisOrchestrator.Result result = new FullAnalysisOrchestrator(
                scene, review, scoring, insight, recommendation)
                .analyze(101L, false, factorPopulation);

        InOrder order = inOrder(review, scene, scoring, insight, recommendation);
        order.verify(review).analyze(101L, false);
        order.verify(scene).classify(101L, false);
        order.verify(scoring).evaluate(any(), same(factorPopulation));
        order.verify(insight).analyze(101L, 701L, false);
        order.verify(recommendation).recommend(101L, 701L, false);
        assertEquals(0, result.cacheHits());
        assertEquals(701L, result.primaryScoreId());
        ArgumentCaptor<EvaluationCommand> command = ArgumentCaptor.forClass(EvaluationCommand.class);
        verify(scoring).evaluate(command.capture(), same(factorPopulation));
        assertEquals(com.example.ssds.core.domain.SceneType.FESTIVAL, command.getValue().primaryScene());
        assertEquals(com.example.ssds.core.domain.SceneType.SEASONAL, command.getValue().alternativeScene());
        assertEquals(new BigDecimal("0.82"), command.getValue().sceneConfidence());
    }

    @Test
    void marksFullAnalysisIncompleteWhenProductInsightHasNoUsableOutput() {
        SceneClassificationService scene = mock(SceneClassificationService.class);
        ReviewRiskService review = mock(ReviewRiskService.class);
        ProductInsightService insight = mock(ProductInsightService.class);
        RecommendationService recommendation = mock(RecommendationService.class);
        ScoreExecutionService scoring = mock(ScoreExecutionService.class);
        ReviewRiskResponse reviewResponse = mock(ReviewRiskResponse.class);
        SceneClassificationResponse sceneResponse = mock(SceneClassificationResponse.class);
        ProductInsightResponse insightResponse = mock(ProductInsightResponse.class);
        RecommendationResponse recommendationResponse = mock(RecommendationResponse.class);
        when(review.analyze(102L, false)).thenReturn(reviewResponse);
        when(reviewResponse.statusMessage())
                .thenReturn("評論樣本不足（少於 20 則），評論風險扣分計為 0");
        when(reviewResponse.analysisCompleted()).thenReturn(true);
        when(scene.classify(102L, false)).thenReturn(sceneResponse);
        stubScene(sceneResponse);
        when(scoring.evaluate(any())).thenReturn(scored(702L));
        when(insight.analyze(102L, 702L, false)).thenReturn(insightResponse);
        when(insightResponse.statusMessage()).thenReturn("賣點證據不足／風險證據不足");
        when(recommendation.recommend(102L, 702L, false)).thenReturn(recommendationResponse);

        FullAnalysisOrchestrator.Result result = new FullAnalysisOrchestrator(
                scene, review, scoring, insight, recommendation).analyze(102L, false);

        assertEquals(
                "評論樣本不足（少於 20 則），評論風險扣分計為 0 "
                        + "賣點證據不足／風險證據不足",
                result.warning());
        assertFalse(result.analysisCompleted());
        verify(recommendation).recommend(102L, 702L, false);
    }

    @Test
    void marksFullAnalysisIncompleteWhenReviewRiskHasNoUsableOutput() {
        SceneClassificationService scene = mock(SceneClassificationService.class);
        ReviewRiskService review = mock(ReviewRiskService.class);
        ProductInsightService insight = mock(ProductInsightService.class);
        RecommendationService recommendation = mock(RecommendationService.class);
        ScoreExecutionService scoring = mock(ScoreExecutionService.class);
        ReviewRiskResponse reviewResponse = mock(ReviewRiskResponse.class);
        SceneClassificationResponse sceneResponse = mock(SceneClassificationResponse.class);
        ProductInsightResponse insightResponse = mock(ProductInsightResponse.class);
        RecommendationResponse recommendationResponse = mock(RecommendationResponse.class);
        when(review.analyze(104L, false)).thenReturn(reviewResponse);
        when(reviewResponse.analysisCompleted()).thenReturn(false);
        when(reviewResponse.statusMessage())
                .thenReturn("評論風險分析未執行：無評論資料，評論風險扣分計為 0");
        when(scene.classify(104L, false)).thenReturn(sceneResponse);
        stubScene(sceneResponse);
        when(scoring.evaluate(any())).thenReturn(scored(704L));
        when(insight.analyze(104L, 704L, false)).thenReturn(insightResponse);
        when(insightResponse.analysisCompleted()).thenReturn(true);
        when(recommendation.recommend(104L, 704L, false)).thenReturn(recommendationResponse);

        FullAnalysisOrchestrator.Result result = new FullAnalysisOrchestrator(
                scene, review, scoring, insight, recommendation).analyze(104L, false);

        assertEquals(
                "評論風險分析未執行：無評論資料，評論風險扣分計為 0",
                result.warning());
        assertFalse(result.analysisCompleted());
        verify(insight).analyze(104L, 704L, false);
        verify(recommendation).recommend(104L, 704L, false);
    }

    @Test
    void stopsScoreDependentAgentsWhenScoringDataIsInsufficient() {
        SceneClassificationService scene = mock(SceneClassificationService.class);
        ReviewRiskService review = mock(ReviewRiskService.class);
        ProductInsightService insight = mock(ProductInsightService.class);
        RecommendationService recommendation = mock(RecommendationService.class);
        ScoreExecutionService scoring = mock(ScoreExecutionService.class);
        ReviewRiskResponse reviewResponse = mock(ReviewRiskResponse.class);
        SceneClassificationResponse sceneResponse = mock(SceneClassificationResponse.class);
        when(review.analyze(103L, false)).thenReturn(reviewResponse);
        when(reviewResponse.statusMessage()).thenReturn("無評論資料");
        when(scene.classify(103L, false)).thenReturn(sceneResponse);
        stubScene(sceneResponse);
        when(scoring.evaluate(any())).thenReturn(new EvaluationResult(
                LastScoringStatus.INSUFFICIENT_DATA,
                "2026W38",
                9L,
                null,
                List.of(),
                "六項加分因子缺少四項以上，無法產生分數"));

        var exception = assertThrows(
                FullAnalysisOrchestrator.FullAnalysisIncompleteException.class,
                () -> new FullAnalysisOrchestrator(
                        scene, review, scoring, insight, recommendation).analyze(103L, false));

        assertEquals("無評論資料 六項加分因子缺少四項以上，無法產生分數", exception.getMessage());
        verifyNoInteractions(insight, recommendation);
    }

    @Test
    void keepsTheCommittedScoreWhenAReadOnlyDownstreamAgentFails() {
        SceneClassificationService scene = mock(SceneClassificationService.class);
        ReviewRiskService review = mock(ReviewRiskService.class);
        ProductInsightService insight = mock(ProductInsightService.class);
        RecommendationService recommendation = mock(RecommendationService.class);
        ScoreExecutionService scoring = mock(ScoreExecutionService.class);
        ReviewRiskResponse reviewResponse = mock(ReviewRiskResponse.class);
        SceneClassificationResponse sceneResponse = mock(SceneClassificationResponse.class);
        when(review.analyze(105L, false)).thenReturn(reviewResponse);
        when(scene.classify(105L, false)).thenReturn(sceneResponse);
        stubScene(sceneResponse);
        when(scoring.evaluate(any())).thenReturn(scored(705L));
        when(insight.analyze(105L, 705L, false)).thenThrow(new IllegalStateException("agent failed"));

        assertThrows(IllegalStateException.class, () -> new FullAnalysisOrchestrator(
                scene, review, scoring, insight, recommendation).analyze(105L, false));

        InOrder order = inOrder(review, scene, scoring, insight);
        order.verify(review).analyze(105L, false);
        order.verify(scene).classify(105L, false);
        order.verify(scoring).evaluate(any());
        order.verify(insight).analyze(105L, 705L, false);
        verifyNoInteractions(recommendation);
    }

    @Test
    void preservesMissingConfidenceAndMarksFallbackExplicitly() {
        SceneClassificationService scene = mock(SceneClassificationService.class);
        ReviewRiskService review = mock(ReviewRiskService.class);
        ProductInsightService insight = mock(ProductInsightService.class);
        RecommendationService recommendation = mock(RecommendationService.class);
        ScoreExecutionService scoring = mock(ScoreExecutionService.class);
        ReviewRiskResponse reviewResponse = mock(ReviewRiskResponse.class);
        SceneClassificationResponse sceneResponse = mock(SceneClassificationResponse.class);
        ProductInsightResponse insightResponse = mock(ProductInsightResponse.class);
        RecommendationResponse recommendationResponse = mock(RecommendationResponse.class);
        when(review.analyze(106L, true)).thenReturn(reviewResponse);
        when(reviewResponse.analysisCompleted()).thenReturn(true);
        when(scene.classify(106L, true)).thenReturn(sceneResponse);
        when(sceneResponse.sceneType()).thenReturn(SceneCode.REPLENISHMENT);
        when(sceneResponse.fallbackApplied()).thenReturn(true);
        when(sceneResponse.confidence()).thenReturn(null);
        when(scoring.evaluate(any())).thenReturn(scored(706L));
        when(insight.analyze(106L, 706L, true)).thenReturn(insightResponse);
        when(insightResponse.analysisCompleted()).thenReturn(true);
        when(recommendation.recommend(106L, 706L, true)).thenReturn(recommendationResponse);

        new FullAnalysisOrchestrator(scene, review, scoring, insight, recommendation)
                .analyze(106L, true);

        ArgumentCaptor<EvaluationCommand> command = ArgumentCaptor.forClass(EvaluationCommand.class);
        verify(scoring).evaluate(command.capture());
        assertEquals(SceneType.REPLENISHMENT, command.getValue().primaryScene());
        assertEquals(null, command.getValue().sceneConfidence());
        assertEquals(true, command.getValue().sceneFallbackApplied());
        assertEquals(null, command.getValue().alternativeScene());
    }

    private static void stubScene(SceneClassificationResponse response) {
        when(response.sceneType()).thenReturn(SceneCode.FESTIVAL);
        when(response.alternativeScene()).thenReturn(SceneCode.SEASONAL);
        when(response.confidence()).thenReturn(new BigDecimal("0.82"));
    }

    private static EvaluationResult scored(Long primaryScoreId) {
        return new EvaluationResult(
                LastScoringStatus.SCORED,
                "2026W38",
                9L,
                primaryScoreId,
                List.of(),
                null);
    }
}
