package com.example.ssds.api.aitask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import com.example.ssds.api.insight.ProductInsightService;
import com.example.ssds.api.insight.dto.ProductInsightResponse;
import com.example.ssds.api.recommendation.RecommendationService;
import com.example.ssds.api.recommendation.dto.RecommendationResponse;
import com.example.ssds.api.review.ReviewRiskService;
import com.example.ssds.api.review.dto.ReviewRiskResponse;
import com.example.ssds.api.scene.SceneClassificationService;
import com.example.ssds.api.scene.dto.SceneClassificationResponse;
import com.example.ssds.api.scoring.ScoreRecalculationService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class FullAnalysisOrchestratorTest {
    @Test
    void runsPreScoringAgentsBeforeScoreDependentAgents() {
        SceneClassificationService scene = mock(SceneClassificationService.class);
        ReviewRiskService review = mock(ReviewRiskService.class);
        ProductInsightService insight = mock(ProductInsightService.class);
        RecommendationService recommendation = mock(RecommendationService.class);
        ScoreRecalculationService scoring = mock(ScoreRecalculationService.class);
        ReviewRiskResponse reviewResponse = mock(ReviewRiskResponse.class);
        SceneClassificationResponse sceneResponse = mock(SceneClassificationResponse.class);
        ProductInsightResponse insightResponse = mock(ProductInsightResponse.class);
        RecommendationResponse recommendationResponse = mock(RecommendationResponse.class);
        when(review.analyze(101L, false)).thenReturn(reviewResponse);
        when(scene.classify(101L, false)).thenReturn(sceneResponse);
        when(insight.analyze(101L, false)).thenReturn(insightResponse);
        when(recommendation.recommend(101L, false)).thenReturn(recommendationResponse);
        when(insightResponse.analysisCompleted()).thenReturn(true);

        FullAnalysisOrchestrator.Result result = new FullAnalysisOrchestrator(
                scene, review, scoring, insight, recommendation).analyze(101L, false);

        InOrder order = inOrder(review, scene, scoring, insight, recommendation);
        order.verify(review).analyze(101L, false);
        order.verify(scene).classify(101L, false);
        order.verify(scoring).recalculate(101L, sceneResponse);
        order.verify(insight).analyze(101L, false);
        order.verify(recommendation).recommend(101L, false);
        assertEquals(0, result.cacheHits());
    }

    @Test
    void keepsAgentSpecificMessagesWhenReviewsAreAbsent() {
        SceneClassificationService scene = mock(SceneClassificationService.class);
        ReviewRiskService review = mock(ReviewRiskService.class);
        ProductInsightService insight = mock(ProductInsightService.class);
        RecommendationService recommendation = mock(RecommendationService.class);
        ScoreRecalculationService scoring = mock(ScoreRecalculationService.class);
        ReviewRiskResponse reviewResponse = mock(ReviewRiskResponse.class);
        SceneClassificationResponse sceneResponse = mock(SceneClassificationResponse.class);
        ProductInsightResponse insightResponse = mock(ProductInsightResponse.class);
        RecommendationResponse recommendationResponse = mock(RecommendationResponse.class);
        when(review.analyze(102L, false)).thenReturn(reviewResponse);
        when(reviewResponse.statusMessage())
                .thenReturn("評論風險分析未執行：無評論資料，評論風險扣分計為 0");
        when(scene.classify(102L, false)).thenReturn(sceneResponse);
        when(insight.analyze(102L, false)).thenReturn(insightResponse);
        when(insightResponse.statusMessage()).thenReturn("賣點與風險分析未執行：無評論資料");
        when(recommendation.recommend(102L, false)).thenReturn(recommendationResponse);

        FullAnalysisOrchestrator.Result result = new FullAnalysisOrchestrator(
                scene, review, scoring, insight, recommendation).analyze(102L, false);

        assertEquals(
                "評論風險分析未執行：無評論資料，評論風險扣分計為 0 "
                        + "賣點與風險分析未執行：無評論資料",
                result.warning());
    }
}
