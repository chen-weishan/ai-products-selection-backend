package com.example.ssds.api.aitask.fullanalysis;

import com.example.ssds.api.insight.ProductInsightService;
import com.example.ssds.api.recommendation.RecommendationService;
import com.example.ssds.api.review.ReviewRiskService;
import com.example.ssds.api.scene.SceneClassificationService;
import com.example.ssds.api.scoring.ScoreRecalculationService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 每品項 Agent 1–4 編排。
 *
 * <p>ReviewRisk 與 SceneClassifier 先產生評分所需輸入；ProductInsight 與
 * Recommendation 再讀取 active primary score。核心六因子評分器由 ssds-core
 * 提供後，應插入兩段之間；目前下游服務會明確拒絕不存在的 active score。
 */
@Service
public class FullAnalysisOrchestrator {
    private final SceneClassificationService scene;
    private final ReviewRiskService reviewRisk;
    private final ProductInsightService productInsight;
    private final RecommendationService recommendation;
    private final ScoreRecalculationService scoring;

    public FullAnalysisOrchestrator(
            SceneClassificationService scene,
            ReviewRiskService reviewRisk,
            ScoreRecalculationService scoring,
            ProductInsightService productInsight,
            RecommendationService recommendation) {
        this.scene = scene;
        this.reviewRisk = reviewRisk;
        this.scoring = scoring;
        this.productInsight = productInsight;
        this.recommendation = recommendation;
    }

    public Result analyze(Long productId, boolean forceRefresh) {
        List<String> warnings = new ArrayList<>();

        var review = reviewRisk.analyze(productId, forceRefresh);
        if (review.statusMessage() != null && !review.statusMessage().isBlank()) {
            warnings.add(review.statusMessage());
        } else if (!review.analysisCompleted()) {
            warnings.add("評論風險分析未完成");
        }

        var classification = scene.classify(productId, forceRefresh);
        if (classification.fallbackApplied()) warnings.add("情境判定已使用 REPLENISHMENT 降級值");

        var scoringResult = scoring.recalculate(productId, classification);
        if (!scoringResult.scored()) {
            warnings.add(scoringResult.message());
            throw new FullAnalysisIncompleteException(String.join(" ", warnings));
        }
        var insight = productInsight.analyze(productId, forceRefresh);
        if (!insight.analysisCompleted()) {
            warnings.add(insight.statusMessage() == null || insight.statusMessage().isBlank()
                    ? "賣點與風險分析未完成"
                    : insight.statusMessage());
        }

        var advice = recommendation.recommend(productId, forceRefresh);
        if (advice.fallbackApplied()) warnings.add("進貨建議已使用規則式降級值");

        int cacheHits = 0;
        if (review.cacheHit()) cacheHits++;
        if (classification.cacheHit()) cacheHits++;
        if (insight.cacheHit()) cacheHits++;
        if (advice.cacheHit()) cacheHits++;
        return new Result(
                cacheHits,
                String.join(" ", warnings),
                review.analysisCompleted() && insight.analysisCompleted());
    }

    public record Result(int cacheHits, String warning, boolean analysisCompleted) {
        public Result(int cacheHits, String warning) {
            this(cacheHits, warning, true);
        }
    }

    /** 可辨識的業務失敗：資料狀態已落地，但本次 FULL_ANALYSIS 未完成。 */
    public static final class FullAnalysisIncompleteException extends RuntimeException {
        public FullAnalysisIncompleteException(String message) {
            super(message);
        }
    }
}
