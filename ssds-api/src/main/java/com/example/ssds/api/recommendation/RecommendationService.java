package com.example.ssds.api.recommendation;

import com.example.ssds.ai.agent.RecommendationAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.ai.prompt.RecommendationPromptFactory;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.recommendation.dto.RecommendationResponse;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
    private static final String MODEL_ALIAS = "MODEL_SHORT_GEN";
    private static final List<FactorCode> BONUS_FACTORS = List.of(
            FactorCode.TREND,
            FactorCode.MARGIN,
            FactorCode.CVR,
            FactorCode.PRICE_FIT,
            FactorCode.FESTIVAL,
            FactorCode.CLIMATE);

    private final ProductRepository productRepository;
    private final ProductScoreRepository scoreRepository;
    private final ItemFestivalAffinityRepository affinityRepository;
    private final FestivalCalendarRepository festivalRepository;
    private final AiInsightRepository insightRepository;
    private final PromptSanitizer promptSanitizer;
    private final RecommendationAgent agent;
    private final ObjectMapper objectMapper;

    public RecommendationService(
            ProductRepository productRepository,
            ProductScoreRepository scoreRepository,
            ItemFestivalAffinityRepository affinityRepository,
            FestivalCalendarRepository festivalRepository,
            AiInsightRepository insightRepository,
            PromptSanitizer promptSanitizer,
            RecommendationAgent agent,
            ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.scoreRepository = scoreRepository;
        this.affinityRepository = affinityRepository;
        this.festivalRepository = festivalRepository;
        this.insightRepository = insightRepository;
        this.promptSanitizer = promptSanitizer;
        this.agent = agent;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RecommendationResponse recommend(Long productId, boolean forceRefresh) {
        Product product = loadTrackAProduct(productId);
        ProductScore score = scoreRepository
                .findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(productId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "此品項尚無可供進貨建議使用的主情境評分"));
        RecommendationInput input = promptSanitizer.sanitizeRecommendation(buildInput(product, score));
        RecommendationResult result = agent.recommend(input, forceRefresh);
        Instant generatedAt = Instant.now();
        persist(product, result, generatedAt);
        return RecommendationResponse.from(
                productId,
                result,
                generatedAt.atZone(BUSINESS_ZONE).toOffsetDateTime());
    }

    @Transactional(readOnly = true)
    public RecommendationResponse latest(Long productId) {
        loadTrackAProduct(productId);
        AiInsight insight = insightRepository
                .findByProductIdAndInsightTypeAndCurrentTrue(productId, InsightType.RECOMMENDATION)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "此品項尚無進貨建議"));
        if (!RecommendationPromptFactory.PROMPT_VERSION.equals(insight.getPromptVersion())) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "此品項尚無 v3.0 進貨建議");
        }
        RecommendationOutput output = readOutput(insight.getContentJson());
        boolean fallback = "rule-fallback".equals(insight.getModel());
        return new RecommendationResponse(
                productId,
                output.action(),
                output.qtyMin(),
                output.qtyMax(),
                output.quantityText(),
                output.reasoning(),
                fallback,
                fallback ? "RULE_FALLBACK" : null,
                insight.isFromCache(),
                insight.getModel(),
                insight.getModelAlias(),
                insight.getPromptVersion(),
                insight.getRequestCount(),
                insight.getGeneratedAt().atZone(BUSINESS_ZONE).toOffsetDateTime());
    }

    private Product loadTrackAProduct(Long productId) {
        Product product = productRepository.findWithDetailsById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的品項"));
        if (product.getTrackType() != TrackType.A) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION, "RecommendationAgent 僅接受 A 軌品項");
        }
        return product;
    }

    private RecommendationInput buildInput(Product product, ProductScore score) {
        Map<FactorCode, ScoreFactor> factors = score.getFactors().stream()
                .collect(Collectors.toMap(
                        ScoreFactor::getFactorCode,
                        Function.identity(),
                        (first, ignored) -> first,
                        () -> new EnumMap<>(FactorCode.class)));
        List<RecommendationInput.FactorPercentile> percentiles = BONUS_FACTORS.stream()
                .map(code -> {
                    ScoreFactor factor = factors.get(code);
                    return new RecommendationInput.FactorPercentile(
                            code,
                            factor == null ? null : factor.getNormalizedValue(),
                            factor != null && factor.isDataAvailable());
                })
                .toList();
        List<FactorCode> matchedPenaltyRules = score.getFactors().stream()
                .filter(ScoreFactor::isPenalty)
                .filter(factor -> factor.getPenaltyValue() != null && factor.getPenaltyValue().signum() > 0)
                .map(ScoreFactor::getFactorCode)
                .distinct()
                .toList();
        return new RecommendationInput(
                product.getId(),
                percentiles,
                score.getBonusSubtotal(),
                score.getPenaltySubtotal(),
                score.getGrade(),
                score.getSceneType(),
                matchedPenaltyRules,
                upcomingFestival(product.getId()),
                allowedQuantities(product.getMoq()));
    }

    private RecommendationInput.FestivalWindow upcomingFestival(Long productId) {
        Set<String> codes = affinityRepository.findByProductId(productId).stream()
                .filter(value -> value.getAffinity() != null && value.getAffinity().signum() > 0)
                .map(ItemFestivalAffinity::getFestivalCode)
                .collect(Collectors.toSet());
        if (codes.isEmpty()) return null;
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        return festivalRepository
                .findByFestivalDateBetweenOrderByFestivalDateAsc(today, today.plusDays(365))
                .stream()
                .filter(value -> codes.contains(value.getFestivalCode()))
                .findFirst()
                .map(value -> new RecommendationInput.FestivalWindow(
                        value.getFestivalCode(),
                        value.getFestivalName(),
                        Math.toIntExact(ChronoUnit.DAYS.between(today, value.getFestivalDate()))))
                .orElse(null);
    }

    private static List<Integer> allowedQuantities(Integer moq) {
        TreeSet<Integer> allowed = new TreeSet<>();
        allowed.add(0);
        if (moq != null && moq > 0) {
            allowed.add(moq);
            long upper = (moq * 3L + 1L) / 2L;
            allowed.add((int) Math.min(Integer.MAX_VALUE, upper));
        }
        return List.copyOf(allowed);
    }

    private void persist(Product product, RecommendationResult result, Instant generatedAt) {
        insightRepository.demoteCurrent(product.getId(), InsightType.RECOMMENDATION);
        insightRepository.flush();
        AiInsight insight = AiInsight.builder()
                .product(product)
                .insightType(InsightType.RECOMMENDATION)
                .contentJson(writeOutput(result.output()))
                .model(result.fallbackApplied() ? "rule-fallback" : result.model())
                .modelAlias(MODEL_ALIAS)
                .promptVersion(RecommendationPromptFactory.PROMPT_VERSION)
                .sourceReviewCount(0)
                .promptTokens(result.promptTokens())
                .completionTokens(result.completionTokens())
                .requestCount((short) result.requestCount())
                .fromCache(result.cacheHit())
                .costUsd(BigDecimal.ZERO)
                .generatedAt(generatedAt)
                .current(true)
                .build();
        insightRepository.save(insight);
    }

    private String writeOutput(RecommendationOutput output) {
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("無法序列化 Recommendation 結果", exception);
        }
    }

    private RecommendationOutput readOutput(String json) {
        try {
            return objectMapper.readValue(json, RecommendationOutput.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("既有 Recommendation JSON 格式錯誤", exception);
        }
    }
}
