package com.example.ssds.ai.prompt;

import com.example.ssds.ai.model.FestivalMatch;
import com.example.ssds.ai.model.ProductInsightInput;
import com.example.ssds.ai.model.RecommendationInput;
import com.example.ssds.ai.model.ReviewRiskInput;
import com.example.ssds.ai.model.SceneClassifierInput;
import com.example.ssds.ai.model.TrendInterpreterInput;
import com.example.ssds.ai.model.SourcingScoutInput;
import com.example.ssds.ai.model.WeightCalibrationInput;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 所有評論送往外部 LLM 前的集中式去識別化入口。 */
@Component
public class PromptSanitizer {
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern ACCOUNT = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])[@#][\\p{L}\\p{N}_.-]+");
    private static final Pattern ORDER = Pattern.compile(
            "\\b(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]{8,}\\b");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?886[-\\s]?)?(?:0?9\\d{2}[-\\s]?\\d{3}[-\\s]?\\d{3}|0\\d{1,2}[-\\s]?\\d{3,4}[-\\s]?\\d{4})(?!\\d)");
    private static final Pattern ADDRESS = Pattern.compile(
            "[\\p{IsHan}]{1,12}(?:縣|市|區|鄉|鎮|村|里)?[\\p{IsHan}0-9]{1,16}(?:路|街|巷|弄)[\\p{IsHan}0-9-]{0,16}(?:號(?:之\\d+)?)?");

    /** Agent 1 白名單：品項／品類識別資訊、熱度訊號、歷史開團數與節慶匹配。 */
    public SceneClassifierInput sanitizeSceneClassifier(SceneClassifierInput input) {
        List<FestivalMatch> festivalMatches = input.festivalMatches().stream()
                .map(value -> new FestivalMatch(safeLabel(value.festivalCode(), 32), value.affinity()))
                .toList();
        return new SceneClassifierInput(
                input.productId(),
                safeLabel(input.productName(), 100),
                input.categoryId(),
                safeLabel(input.categoryName(), 100),
                input.season(),
                input.heatSlope7d(),
                input.heatSlope30d(),
                input.heatSlopePercentile(),
                input.heatStage(),
                input.heatBucket(),
                input.historicalCampaignCount(),
                festivalMatches);
    }

    /** Agent 2 白名單：只傳遞評論識別碼與去識別化後的評論內容。 */
    public ReviewRiskInput sanitizeReviewRisk(Long productId, List<ReviewRiskInput.ReviewText> reviews) {
        List<ReviewRiskInput.ReviewText> sanitized = reviews.stream()
                .map(review -> new ReviewRiskInput.ReviewText(
                        review.reviewId(), sanitizeReviewText(review.content())))
                .toList();
        return new ReviewRiskInput(productId, sanitized);
    }

    /** Agent 3 白名單：品項基本資料、去識別評論及後端已算好的扣分摘要。 */
    public ProductInsightInput sanitizeProductInsight(ProductInsightInput input) {
        ProductInsightInput.ProductBasic product = input.product();
        ProductInsightInput.ProductBasic safeProduct = new ProductInsightInput.ProductBasic(
                safeLabel(product.name(), 100),
                safeLabel(product.category(), 100),
                product.season(),
                safeLabel(product.logisticsCondition(), 100));
        List<ProductInsightInput.ReviewText> reviews = input.reviews().stream()
                .map(review -> new ProductInsightInput.ReviewText(
                        review.reviewId(), sanitizeReviewText(review.content())))
                .toList();
        return new ProductInsightInput(input.productId(), safeProduct, reviews, input.penalties());
    }

    /** Agent 4 白名單：六因子百分位與評分摘要；不接受任何原始量值或品項機敏欄位。 */
    public RecommendationInput sanitizeRecommendation(RecommendationInput input) {
        RecommendationInput.FestivalWindow festival = input.festival() == null
                ? null
                : new RecommendationInput.FestivalWindow(
                        safeLabel(input.festival().festivalCode(), 32),
                        safeLabel(input.festival().festivalName(), 50),
                        input.festival().daysRemaining());
        return new RecommendationInput(
                input.productId(),
                input.factors(),
                input.bonusSubtotal(),
                input.penaltySubtotal(),
                input.grade(),
                input.sceneType(),
                input.matchedPenaltyRules(),
                festival,
                input.allowedQuantities());
    }

    /** Agent 5 白名單：合成時序、來源斜率／可用性及後端允許的輸出組合。 */
    public TrendInterpreterInput sanitizeTrendInterpreter(TrendInterpreterInput input) {
        return new TrendInterpreterInput(
                input.keywordId(),
                input.compositeSeries(),
                input.sourceTrends(),
                input.allowedOutputs());
    }

    /** Agent 6 白名單：只允許關鍵字及品類識別資訊進入 B 軌探索 Prompt。 */
    public SourcingScoutInput sanitizeSourcingScout(SourcingScoutInput input) {
        return new SourcingScoutInput(
                safeLabel(input.keyword(), 80), input.categoryId(), safeLabel(input.categoryName(), 50));
    }

    /** Agent 7 僅傳彙總統計；型別本身沒有逐筆銷售、品項、會員或供應商欄位。 */
    public WeightCalibrationInput sanitizeWeightCalibration(WeightCalibrationInput input) {
        List<WeightCalibrationInput.FactorStatistic> factors = input.factors().stream()
                .map(value -> new WeightCalibrationInput.FactorStatistic(
                        safeLabel(value.factorCode(), 32), value.correlation(), value.currentWeight(),
                        value.suggestedWeight(), value.pValue()))
                .toList();
        WeightCalibrationInput.OverrideStatistics overrides = input.sceneOverrides();
        List<WeightCalibrationInput.CategoryOverrideStatistic> categories = overrides.concentratedCategories().stream()
                .map(value -> new WeightCalibrationInput.CategoryOverrideStatistic(
                        safeLabel(value.category(), 50), value.totalClassifications(),
                        value.overrideCount(), value.overrideRate()))
                .toList();
        List<WeightCalibrationInput.BacktestStatistic> backtests = input.backtests().stream()
                .map(value -> new WeightCalibrationInput.BacktestStatistic(
                        safeLabel(value.scheme(), 40), value.correlation(), value.gradeAHitRate()))
                .toList();
        return new WeightCalibrationInput(
                safeLabel(input.quarter(), 8), input.sampleSize(), safeLabel(input.regressionMethod(), 40),
                factors, safeLabel(input.regressionNote(), 500),
                new WeightCalibrationInput.OverrideStatistics(overrides.totalClassifications(),
                        overrides.overrideCount(), overrides.overrideRate(), categories),
                backtests, safeLabel(input.backtestNote(), 500));
    }

    public String sanitizeReviewText(String value) {
        if (value == null) return "";
        String sanitized = EMAIL.matcher(value).replaceAll("[EMAIL]");
        sanitized = ACCOUNT.matcher(sanitized).replaceAll("@USER");
        sanitized = ORDER.matcher(sanitized).replaceAll("[ORDER]");
        sanitized = PHONE.matcher(sanitized).replaceAll("[PHONE]");
        return ADDRESS.matcher(sanitized).replaceAll("[ADDRESS]");
    }

    private static String safeLabel(String value, int maxLength) {
        if (value == null) return null;
        String sanitized = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
    }
}
