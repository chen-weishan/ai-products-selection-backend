package com.example.ssds.api.scene;

import com.example.ssds.ai.agent.SceneClassifierAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.scene.dto.SceneClassificationResponse;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.HeatReading;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.repository.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SceneClassificationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
    private static final BigDecimal SLOPE_EPSILON = new BigDecimal("0.0001");

    private final ProductRepository productRepository;
    private final HeatReadingRepository heatReadingRepository;
    private final DecisionRecordRepository decisionRecordRepository;
    private final ItemFestivalAffinityRepository festivalAffinityRepository;
    private final SceneClassificationLogRepository logRepository;
    private final SceneClassifierAgent agent;

    public SceneClassificationService(
            ProductRepository productRepository,
            HeatReadingRepository heatReadingRepository,
            DecisionRecordRepository decisionRecordRepository,
            ItemFestivalAffinityRepository festivalAffinityRepository,
            SceneClassificationLogRepository logRepository,
            SceneClassifierAgent agent) {
        this.productRepository = productRepository;
        this.heatReadingRepository = heatReadingRepository;
        this.decisionRecordRepository = decisionRecordRepository;
        this.festivalAffinityRepository = festivalAffinityRepository;
        this.logRepository = logRepository;
        this.agent = agent;
    }

    @Transactional
    public SceneClassificationResponse classify(Long productId, boolean forceRefresh) {
        Product product = loadTrackAProduct(productId);
        SceneClassifierInput input = buildInput(product);
        SceneClassificationResult result = agent.classify(input, forceRefresh);
        SceneClassificationLog log = SceneClassificationLog.builder()
                .product(product)
                .aiSceneType(result.fallbackApplied() ? null : result.output().sceneType().toDomain())
                .aiConfidence(result.output().confidence())
                .aiReasoning(result.output().reasoning())
                .alternativeSceneType(result.output().alternativeScene() == null
                        ? null : result.output().alternativeScene().toDomain())
                .signals(result.output().signals())
                .finalSceneType(result.output().sceneType().toDomain())
                .fallbackApplied(result.fallbackApplied())
                .fallbackReason(result.fallbackReason() == null ? null : result.fallbackReason().name())
                .model(result.model())
                .promptVersion(result.promptVersion())
                .heatBucket(input.heatBucket().name())
                .build();
        SceneClassificationLog saved = logRepository.save(log);
        return SceneClassificationResponse.from(saved, result);
    }

    @Transactional(readOnly = true)
    public SceneClassificationResponse latest(Long productId) {
        loadTrackAProduct(productId);
        return logRepository.findFirstByProductIdOrderByCreatedAtDesc(productId)
                .map(SceneClassificationResponse::from)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "此品項尚無情境判定結果"));
    }

    private Product loadTrackAProduct(Long productId) {
        Product product = productRepository.findWithDetailsById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的品項"));
        if (product.getTrackType() != TrackType.A) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "SceneClassifierAgent 僅接受 A 軌品項");
        }
        return product;
    }

    private SceneClassifierInput buildInput(Product product) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        TreeMap<LocalDate, List<BigDecimal>> dailyValues = new TreeMap<>();
        product.getKeywords().forEach(keyword -> heatReadingRepository
                .findByKeywordIdAndReadingDateBetweenOrderByReadingDateAsc(
                        keyword.getId(), today.minusDays(30), today)
                .stream()
                .filter(reading -> reading.getPercentileWithinSource() != null)
                .forEach(reading -> dailyValues
                        .computeIfAbsent(reading.getReadingDate(), ignored -> new ArrayList<>())
                        .add(reading.getPercentileWithinSource())));

        TreeMap<LocalDate, BigDecimal> series = new TreeMap<>();
        dailyValues.forEach((date, values) -> series.put(date, average(values)));
        BigDecimal latest = series.isEmpty() ? null : series.lastEntry().getValue();
        LocalDate latestDate = series.isEmpty() ? today : series.lastKey();

        List<FestivalMatch> festivalMatches = festivalAffinityRepository.findByProductId(product.getId())
                .stream()
                .map(value -> new FestivalMatch(value.getFestivalCode(), value.getAffinity()))
                .toList();

        return new SceneClassifierInput(
                product.getId(),
                product.getName(),
                product.getCategory().getId(),
                product.getCategory().getName(),
                product.getSeason(),
                slope(series, latestDate, 7),
                slope(series, latestDate, 30),
                latest,
                HeatBucket.fromPercentile(latest),
                decisionRecordRepository.countByProductId(product.getId()),
                festivalMatches);
    }

    private static BigDecimal slope(TreeMap<LocalDate, BigDecimal> series, LocalDate latestDate, int days) {
        if (series.isEmpty()) return null;
        Map.Entry<LocalDate, BigDecimal> prior = series.floorEntry(latestDate.minusDays(days));
        if (prior == null) return null;
        BigDecimal latest = series.lastEntry().getValue();
        BigDecimal denominator = prior.getValue().abs().max(SLOPE_EPSILON);
        return latest.subtract(prior.getValue()).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }
}
