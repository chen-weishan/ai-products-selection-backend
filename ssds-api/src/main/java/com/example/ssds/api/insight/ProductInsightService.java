package com.example.ssds.api.insight;

import com.example.ssds.ai.agent.ProductInsightAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.ProductInsightPromptFactory;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.insight.dto.ProductInsightResponse;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductInsightService {
    private static final int REVIEW_LIMIT = 200;
    private static final int REVIEW_BUCKET_SIZE = 50;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
    private static final String MODEL_ALIAS = "MODEL_LONG_TEXT";

    private final ProductRepository productRepository;
    private final ProductReviewRepository reviewRepository;
    private final ProductScoreRepository scoreRepository;
    private final AiInsightRepository insightRepository;
    private final PromptSanitizer promptSanitizer;
    private final ProductInsightAgent agent;
    private final ObjectMapper objectMapper;

    public ProductInsightService(
            ProductRepository productRepository,
            ProductReviewRepository reviewRepository,
            ProductScoreRepository scoreRepository,
            AiInsightRepository insightRepository,
            PromptSanitizer promptSanitizer,
            ProductInsightAgent agent,
            ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.scoreRepository = scoreRepository;
        this.insightRepository = insightRepository;
        this.promptSanitizer = promptSanitizer;
        this.agent = agent;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProductInsightResponse analyze(Long productId, boolean forceRefresh) {
        Product product = loadTrackAProduct(productId);
        List<ProductReview> reviews = latestReviews(productId);
        long totalReviewCount = reviewRepository.countByProductId(productId);
        ProductInsightInput input = promptSanitizer.sanitizeProductInsight(
                buildInput(product, reviews));
        ProductInsightResult result = agent.analyze(
                input,
                Math.toIntExact(totalReviewCount / REVIEW_BUCKET_SIZE),
                latestReviewDate(reviews),
                forceRefresh);
        Instant generatedAt = Instant.now();
        if (!result.fallbackApplied() && !result.output().sellingPoints().isEmpty()) {
            persist(product, reviews.size(), result, generatedAt);
        }
        return ProductInsightResponse.from(
                productId,
                reviews.size(),
                result,
                generatedAt.atZone(BUSINESS_ZONE).toOffsetDateTime());
    }

    @Transactional(readOnly = true)
    public ProductInsightResponse latest(Long productId) {
        loadTrackAProduct(productId);
        AiInsight selling = current(productId, InsightType.SELLING_POINT);
        AiInsight risk = current(productId, InsightType.RISK);
        List<SellingPoint> sellingPoints = readList(selling.getContentJson(), "sellingPoints", SellingPoint[].class);
        List<ProductInsightRisk> risks = readList(risk.getContentJson(), "risks", ProductInsightRisk[].class);
        return new ProductInsightResponse(
                productId,
                sellingPoints,
                risks,
                true,
                null,
                false,
                null,
                selling.isFromCache(),
                selling.getModel(),
                selling.getModelAlias(),
                selling.getPromptVersion(),
                selling.getSourceReviewCount(),
                selling.getRequestCount(),
                selling.getGeneratedAt().atZone(BUSINESS_ZONE).toOffsetDateTime());
    }

    private Product loadTrackAProduct(Long productId) {
        Product product = productRepository.findWithDetailsById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的品項"));
        if (product.getTrackType() != TrackType.A) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION, "ProductInsightAgent 僅接受 A 軌品項");
        }
        return product;
    }

    private ProductInsightInput buildInput(Product product, List<ProductReview> reviews) {
        ProductInsightInput.ProductBasic basic = new ProductInsightInput.ProductBasic(
                product.getName(),
                product.getCategory().getName(),
                product.getSeason(),
                product.getLogisticsCondition());
        List<ProductInsightInput.ReviewText> reviewTexts = reviews.stream()
                .map(review -> new ProductInsightInput.ReviewText(review.getId(), review.getContent()))
                .toList();
        List<ProductInsightInput.PenaltyDetail> penalties = penaltyDetails(product.getId(), reviews);
        return new ProductInsightInput(product.getId(), basic, reviewTexts, penalties);
    }

    private List<ProductInsightInput.PenaltyDetail> penaltyDetails(
            Long productId, List<ProductReview> reviews) {
        Map<FactorCode, ScoreFactor> factors = new EnumMap<>(FactorCode.class);
        scoreRepository
                .findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(productId)
                .ifPresent(score -> score.getFactors().stream()
                        .filter(ScoreFactor::isPenalty)
                        .forEach(factor -> factors.put(factor.getFactorCode(), factor)));
        List<String> reviewTopics = reviews.stream()
                .map(ProductReview::getAnalysis)
                .filter(Objects::nonNull)
                .map(ReviewAnalysis::getRiskTopic)
                .filter(topic -> topic == ReviewRiskTopic.QUALITY
                        || topic == ReviewRiskTopic.FOOD_SAFETY
                        || topic == ReviewRiskTopic.SHIPPING_DAMAGE)
                .map(Enum::name)
                .distinct()
                .toList();
        return List.of(
                penalty(FactorCode.REVIEW_RISK, factors.get(FactorCode.REVIEW_RISK), reviewTopics),
                penalty(FactorCode.LOGISTICS_RISK, factors.get(FactorCode.LOGISTICS_RISK), List.of("LOGISTICS")),
                penalty(FactorCode.INVENTORY_RISK, factors.get(FactorCode.INVENTORY_RISK), List.of("INVENTORY")));
    }

    private static ProductInsightInput.PenaltyDetail penalty(
            FactorCode code, ScoreFactor factor, List<String> topics) {
        BigDecimal value = factor == null || factor.getPenaltyValue() == null
                ? BigDecimal.ZERO
                : factor.getPenaltyValue();
        return new ProductInsightInput.PenaltyDetail(
                code, value, value.signum() > 0 ? topics : List.of());
    }

    private List<ProductReview> latestReviews(Long productId) {
        Sort sort = Sort.by(
                Sort.Order.desc("reviewedAt").nullsLast(),
                Sort.Order.desc("id"));
        return reviewRepository
                .findByProductId(productId, PageRequest.of(0, REVIEW_LIMIT, sort))
                .getContent();
    }

    private static LocalDate latestReviewDate(List<ProductReview> reviews) {
        return reviews.stream()
                .map(review -> review.getReviewedAt() != null
                        ? review.getReviewedAt()
                        : review.getCreatedAt() == null
                                ? null
                                : review.getCreatedAt().atZone(BUSINESS_ZONE).toLocalDate())
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private void persist(
            Product product,
            int reviewCount,
            ProductInsightResult result,
            Instant generatedAt) {
        insightRepository.demoteCurrent(product.getId(), InsightType.SELLING_POINT);
        insightRepository.demoteCurrent(product.getId(), InsightType.RISK);
        insightRepository.flush();
        AiInsight selling = insight(
                product,
                InsightType.SELLING_POINT,
                json(Map.of("sellingPoints", result.output().sellingPoints())),
                reviewCount,
                result,
                generatedAt,
                result.requestCount(),
                result.promptTokens(),
                result.completionTokens());
        AiInsight risk = insight(
                product,
                InsightType.RISK,
                json(Map.of("risks", result.output().risks())),
                reviewCount,
                result,
                generatedAt,
                0,
                null,
                null);
        insightRepository.saveAll(List.of(selling, risk));
    }

    private static AiInsight insight(
            Product product,
            InsightType type,
            String contentJson,
            int reviewCount,
            ProductInsightResult result,
            Instant generatedAt,
            int requestCount,
            Integer promptTokens,
            Integer completionTokens) {
        return AiInsight.builder()
                .product(product)
                .insightType(type)
                .contentJson(contentJson)
                .model(result.model())
                .modelAlias(MODEL_ALIAS)
                .promptVersion(ProductInsightPromptFactory.PROMPT_VERSION)
                .sourceReviewCount(reviewCount)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .requestCount((short) requestCount)
                .fromCache(result.cacheHit())
                .costUsd(BigDecimal.ZERO)
                .generatedAt(generatedAt)
                .current(true)
                .build();
    }

    private AiInsight current(Long productId, InsightType type) {
        AiInsight insight = insightRepository.findByProductIdAndInsightTypeAndCurrentTrue(productId, type)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "此品項尚無賣點與風險分析結果"));
        if (!ProductInsightPromptFactory.PROMPT_VERSION.equals(insight.getPromptVersion())) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "此品項尚無 v3.0 賣點與風險分析結果");
        }
        return insight;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("無法序列化 ProductInsight 結果", exception);
        }
    }

    private <T> List<T> readList(String json, String field, Class<T[]> type) {
        try {
            JsonNode node = objectMapper.readTree(json).get(field);
            if (node == null || !node.isArray()) throw new IllegalArgumentException("缺少 " + field);
            return List.of(objectMapper.treeToValue(node, type));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("既有 ProductInsight JSON 格式錯誤", exception);
        }
    }
}
