package com.example.ssds.ai.prompt;

import com.example.ssds.ai.model.ProductInsightInput;
import com.example.ssds.ai.model.RecommendationInput;
import com.example.ssds.ai.model.ReviewRiskInput;
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
