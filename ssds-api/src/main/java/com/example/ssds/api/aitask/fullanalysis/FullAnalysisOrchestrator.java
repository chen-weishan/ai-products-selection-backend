package com.example.ssds.api.aitask.fullanalysis;

import com.example.ssds.api.insight.ProductInsightService;
import com.example.ssds.api.recommendation.RecommendationService;
import com.example.ssds.api.review.ReviewRiskService;
import com.example.ssds.api.scene.SceneClassificationService;
import com.example.ssds.api.scoring.ScoreExecutionService;
import com.example.ssds.api.scoring.ScoreExecutionService.EvaluationCommand;
import com.example.ssds.api.scoring.factor.ScoringFactorBatchService.PreparedPopulation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 每品項 Agent 1–4 編排。
 *
 * <p>ReviewRisk 與 SceneClassifier 先產生評分所需輸入；正式九因子評分完成後，
 * ProductInsight 與 Recommendation 固定讀取本次回傳的 primary scoreId。
 */
@Service
public class FullAnalysisOrchestrator {
    private final SceneClassificationService scene;
    private final ReviewRiskService reviewRisk;
    private final ProductInsightService productInsight;
    private final RecommendationService recommendation;
    private final ScoreExecutionService scoring;

    public FullAnalysisOrchestrator(
            SceneClassificationService scene,
            ReviewRiskService reviewRisk,
            ScoreExecutionService scoring,
            ProductInsightService productInsight,
            RecommendationService recommendation) {
        this.scene = scene;
        this.reviewRisk = reviewRisk;
        this.scoring = scoring;
        this.productInsight = productInsight;
        this.recommendation = recommendation;
    }

    public Result analyze(Long productId, boolean forceRefresh) {
        return analyze(productId, forceRefresh, null);
    }

    public Result analyze(
            Long productId,
            boolean forceRefresh,
            PreparedPopulation factorPopulation) {
        List<String> warnings = new ArrayList<>();

        var review = reviewRisk.analyze(productId, forceRefresh);
        if (review.statusMessage() != null && !review.statusMessage().isBlank()) {
            warnings.add(review.statusMessage());
        } else if (!review.analysisCompleted()) {
            warnings.add("評論風險分析未完成");
        }

        var classification = scene.classify(productId, forceRefresh);
        if (classification.fallbackApplied()) warnings.add("情境判定已使用 REPLENISHMENT 降級值");

        EvaluationCommand command = new EvaluationCommand(
                productId,
                classification.sceneType().toDomain(),
                classification.fallbackApplied() || classification.alternativeScene() == null
                        ? null
                        : classification.alternativeScene().toDomain(),
                classification.confidence(),
                classification.fallbackApplied(),
                Instant.now());
        var scoringResult = factorPopulation == null
                ? scoring.evaluate(command)
                : scoring.evaluate(command, factorPopulation);
        if (!scoringResult.scored()) {
            warnings.add(scoringResult.message());
            throw new FullAnalysisIncompleteException(String.join(" ", warnings));
        }
        Long primaryScoreId = scoringResult.primaryScoreId();
        var insight = productInsight.analyze(productId, primaryScoreId, forceRefresh);
        if (!insight.analysisCompleted()) {
            warnings.add(insight.statusMessage() == null || insight.statusMessage().isBlank()
                    ? "賣點與風險分析未完成"
                    : insight.statusMessage());
        }

        var advice = recommendation.recommend(productId, primaryScoreId, forceRefresh);
        if (advice.fallbackApplied()) warnings.add("進貨建議已使用規則式降級值");

        int cacheHits = 0;
        if (review.cacheHit()) cacheHits++;
        if (classification.cacheHit()) cacheHits++;
        if (insight.cacheHit()) cacheHits++;
        if (advice.cacheHit()) cacheHits++;
        return new Result(
                cacheHits,
                String.join(" ", warnings),
                review.analysisCompleted() && insight.analysisCompleted(),
                primaryScoreId);
    }

    public record Result(
            int cacheHits, String warning, boolean analysisCompleted, Long primaryScoreId) {
        public Result(int cacheHits, String warning, boolean analysisCompleted) {
            this(cacheHits, warning, analysisCompleted, null);
        }

        public Result(int cacheHits, String warning) {
            this(cacheHits, warning, true, null);
        }
    }

    /** 可辨識的業務失敗：資料狀態已落地，但本次 FULL_ANALYSIS 未完成。 */
    public static final class FullAnalysisIncompleteException extends RuntimeException {
        public FullAnalysisIncompleteException(String message) {
            super(message);
        }
    }
}
