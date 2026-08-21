package com.example.ssds.api.review;

import com.example.ssds.ai.agent.ReviewRiskAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.api.exception.BusinessException;
import com.example.ssds.api.exception.ErrorCode;
import com.example.ssds.api.review.dto.ReviewRiskResponse;
import com.example.ssds.core.domain.ReviewRiskTopic;
import com.example.ssds.core.domain.Sentiment;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductReview;
import com.example.ssds.infra.entity.ReviewAnalysis;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductReviewRepository;
import com.example.ssds.infra.repository.ReviewAnalysisRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewRiskService {
    private static final int REVIEW_LIMIT = 200;

    private final ProductRepository productRepository;
    private final ProductReviewRepository productReviewRepository;
    private final ReviewAnalysisRepository reviewAnalysisRepository;
    private final PromptSanitizer promptSanitizer;
    private final ReviewRiskAgent agent;

    public ReviewRiskService(
            ProductRepository productRepository,
            ProductReviewRepository productReviewRepository,
            ReviewAnalysisRepository reviewAnalysisRepository,
            PromptSanitizer promptSanitizer,
            ReviewRiskAgent agent) {
        this.productRepository = productRepository;
        this.productReviewRepository = productReviewRepository;
        this.reviewAnalysisRepository = reviewAnalysisRepository;
        this.promptSanitizer = promptSanitizer;
        this.agent = agent;
    }

    @Transactional
    public ReviewRiskResponse analyze(Long productId, boolean forceRefresh) {
        loadTrackAProduct(productId);
        long totalReviewCount = productReviewRepository.countByProductId(productId);
        Long latestReviewId = productReviewRepository
                .findFirstByProductIdOrderByIdDesc(productId)
                .map(ProductReview::getId)
                .orElse(null);
        List<ProductReview> reviews = latestReviews(productId);
        List<ReviewRiskInput.ReviewText> rawInput = reviews.stream()
                .map(review -> new ReviewRiskInput.ReviewText(review.getId(), review.getContent()))
                .toList();
        ReviewRiskInput input = promptSanitizer.sanitizeReviewRisk(productId, rawInput);
        ReviewRiskResult result = agent.analyze(input, totalReviewCount, latestReviewId, forceRefresh);
        Instant analyzedAt = Instant.now();
        if (!result.fallbackApplied()) {
            persist(reviews, result, analyzedAt);
        }
        return ReviewRiskResponse.from(productId, reviews.size(), result, analyzedAt);
    }

    @Transactional(readOnly = true)
    public ReviewRiskResponse latest(Long productId) {
        loadTrackAProduct(productId);
        List<ProductReview> reviews = latestReviews(productId);
        List<ReviewRiskAnalysis> analyses = reviews.stream()
                .filter(review -> review.getAnalysis() != null)
                .map(review -> new ReviewRiskAnalysis(
                        review.getId(),
                        review.getAnalysis().getSentiment(),
                        persistedTopic(review.getAnalysis())))
                .toList();
        if (!reviews.isEmpty() && analyses.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "此品項尚無評論風險分析結果");
        }
        List<ReviewTopicStatistic> statistics = statisticsFrom(analyses);
        Instant latestAnalyzedAt = reviews.stream()
                .map(ProductReview::getAnalysis)
                .filter(Objects::nonNull)
                .map(ReviewAnalysis::getAnalyzedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new ReviewRiskResponse(
                productId,
                reviews.size(),
                analyses.size(),
                analyses,
                statistics,
                true,
                reviews.isEmpty() ? "評論資料不足" : null,
                null,
                false,
                null,
                false,
                analyses.isEmpty() ? "not-invoked" : reviews.stream()
                        .map(ProductReview::getAnalysis)
                        .filter(Objects::nonNull)
                        .map(ReviewAnalysis::getModel)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null),
                reviews.stream()
                        .map(ProductReview::getAnalysis)
                        .filter(Objects::nonNull)
                        .map(ReviewAnalysis::getPromptVersion)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null),
                latestAnalyzedAt);
    }

    private Product loadTrackAProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的品項"));
        if (product.getTrackType() != TrackType.A) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION, "ReviewRiskAgent 僅接受 A 軌品項");
        }
        return product;
    }

    private List<ProductReview> latestReviews(Long productId) {
        Sort sort = Sort.by(
                Sort.Order.desc("reviewedAt").nullsLast(),
                Sort.Order.desc("id"));
        return productReviewRepository
                .findByProductId(productId, PageRequest.of(0, REVIEW_LIMIT, sort))
                .getContent();
    }

    private void persist(List<ProductReview> reviews, ReviewRiskResult result, Instant analyzedAt) {
        Map<Long, ProductReview> byId = reviews.stream()
                .collect(Collectors.toMap(ProductReview::getId, Function.identity()));
        List<ReviewAnalysis> entities = result.output().reviews().stream().map(value -> {
            ProductReview review = byId.get(value.reviewId());
            ReviewAnalysis entity = review.getAnalysis();
            if (entity == null) {
                entity = ReviewAnalysis.builder().review(review).build();
                review.setAnalysis(entity);
            }
            entity.setSentiment(value.sentiment());
            entity.setRiskTopic(value.riskTopic());
            entity.setKeyPhrase(null);
            entity.setModel(result.model());
            entity.setPromptVersion(result.promptVersion());
            entity.setAnalyzedAt(analyzedAt);
            return entity;
        }).toList();
        reviewAnalysisRepository.saveAll(entities);
    }

    private static ReviewRiskTopic persistedTopic(ReviewAnalysis analysis) {
        if (analysis.getSentiment() != Sentiment.NEGATIVE) return null;
        return analysis.getRiskTopic();
    }

    private static List<ReviewTopicStatistic> statisticsFrom(List<ReviewRiskAnalysis> analyses) {
        long negatives = analyses.stream()
                .filter(value -> value.sentiment() == Sentiment.NEGATIVE)
                .count();
        EnumMap<ReviewRiskTopic, Long> counts = new EnumMap<>(ReviewRiskTopic.class);
        analyses.stream()
                .filter(value -> value.sentiment() == Sentiment.NEGATIVE)
                .forEach(value -> counts.merge(value.riskTopic(), 1L, Long::sum));
        return Arrays.stream(ReviewRiskTopic.values()).map(topic -> {
            BigDecimal ratio = negatives == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(counts.getOrDefault(topic, 0L))
                            .divide(BigDecimal.valueOf(negatives), 4, RoundingMode.HALF_UP);
            Severity severity = ratio.compareTo(new BigDecimal("0.60")) >= 0
                    ? Severity.HIGH
                    : ratio.compareTo(new BigDecimal("0.30")) >= 0
                            ? Severity.MEDIUM
                            : Severity.LOW;
            return new ReviewTopicStatistic(topic, ratio, severity);
        }).toList();
    }
}
