package com.example.ssds.api.scoring;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.model.scene.SceneCode;
import com.example.ssds.api.scene.dto.SceneClassificationResponse;
import com.example.ssds.api.product.service.ProductFallbackScoringService;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.RiskAlertRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScoreRecalculationServiceTest {
    @Test
    void createsPrimaryAndAlternativeScoresFromCurrentFactorSnapshot() {
        ProductScoreRepository scores = mock(ProductScoreRepository.class);
        WeightVersionRepository versions = mock(WeightVersionRepository.class);
        Product product = Product.builder().id(102L).build();
        ProductScore source = sourceScore(product);
        WeightVersion version = versionWithProfiles();
        WeightVersionRepository.GradeThresholdView threshold = mock(
                WeightVersionRepository.GradeThresholdView.class);
        when(threshold.getGradeAMin()).thenReturn(new BigDecimal("80"));
        when(threshold.getGradeBMin()).thenReturn(new BigDecimal("65"));
        when(scores.findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(102L))
                .thenReturn(Optional.of(source));
        when(versions.findByIsCurrentTrue()).thenReturn(Optional.of(version));
        when(versions.findGradeThreshold(eq(1L), anyString())).thenReturn(Optional.of(threshold));
        when(scores.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        SceneClassificationResponse classification = new SceneClassificationResponse(
                9L,
                102L,
                SceneCode.FESTIVAL,
                new BigDecimal("0.82"),
                "節慶匹配",
                SceneCode.SEASONAL,
                List.of("festival"),
                false,
                null,
                false,
                "HIGH",
                "model",
                "MODEL_CLASSIFY",
                "v3",
                null,
                null,
                0,
                null);

        ScoreRecalculationService.Result result = new ScoreRecalculationService(scores, versions)
                .recalculate(102L, classification);

        assertEquals(LastScoringStatus.SCORED, result.status());
        assertEquals(2, result.scores().size());
        assertEquals(SceneType.FESTIVAL, result.scores().get(0).getSceneType());
        assertTrue(result.scores().get(0).isPrimary());
        assertEquals(SceneType.SEASONAL, result.scores().get(1).getSceneType());
        assertFalse(result.scores().get(1).isPrimary());
        assertEquals(9, result.scores().get(0).getFactors().size());
        verify(scores).deactivateCurrentScores(eq(102L), matches("\\d{4}W\\d{2}"));
    }

    @Test
    void createsInitialSnapshotBeforeRecalculatingNewProduct() {
        ProductScoreRepository scores = mock(ProductScoreRepository.class);
        WeightVersionRepository versions = mock(WeightVersionRepository.class);
        ProductRepository products = mock(ProductRepository.class);
        ProductFallbackScoringService initialScoring = mock(ProductFallbackScoringService.class);
        Product product = Product.builder().id(103L).build();
        ProductScore initial = sourceScore(product);
        WeightVersion version = versionWithProfiles();
        WeightVersionRepository.GradeThresholdView threshold = mock(
                WeightVersionRepository.GradeThresholdView.class);
        when(threshold.getGradeAMin()).thenReturn(new BigDecimal("80"));
        when(threshold.getGradeBMin()).thenReturn(new BigDecimal("65"));
        when(scores.findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(103L))
                .thenReturn(Optional.empty());
        when(products.findById(103L)).thenReturn(Optional.of(product));
        when(initialScoring.buildScore(product)).thenReturn(initial);
        when(versions.findByIsCurrentTrue()).thenReturn(Optional.of(version));
        when(versions.findGradeThreshold(eq(1L), anyString())).thenReturn(Optional.of(threshold));
        when(scores.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        SceneClassificationResponse classification = new SceneClassificationResponse(
                10L, 103L, SceneCode.FESTIVAL, new BigDecimal("0.82"), "節慶匹配",
                null, List.of(), false, null, false, "HIGH", "model",
                "MODEL_CLASSIFY", "v3", null, null, 0, null);

        ScoreRecalculationService.Result result = new ScoreRecalculationService(
                scores, versions, products, initialScoring).recalculate(103L, classification);

        assertEquals(1, result.scores().size());
        verify(initialScoring).buildScore(product);
        verify(scores).deactivateCurrentScores(eq(103L), matches("\\d{4}W\\d{2}"));
    }

    @Test
    void recordsInsufficientDataWithoutPersistingAnInvalidScore() {
        ProductScoreRepository scores = mock(ProductScoreRepository.class);
        WeightVersionRepository versions = mock(WeightVersionRepository.class);
        ProductRepository products = mock(ProductRepository.class);
        ProductFallbackScoringService initialScoring = mock(ProductFallbackScoringService.class);
        RiskAlertRepository alerts = mock(RiskAlertRepository.class);
        Product product = Product.builder().id(104L).build();
        ProductScore initial = insufficientSource(product);
        WeightVersion version = versionWithProfiles();
        WeightVersionRepository.GradeThresholdView threshold = mock(
                WeightVersionRepository.GradeThresholdView.class);
        when(threshold.getGradeAMin()).thenReturn(new BigDecimal("80"));
        when(threshold.getGradeBMin()).thenReturn(new BigDecimal("65"));
        when(scores.findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(104L))
                .thenReturn(Optional.empty());
        when(products.findById(104L)).thenReturn(Optional.of(product));
        when(initialScoring.buildScore(product)).thenReturn(initial);
        when(versions.findByIsCurrentTrue()).thenReturn(Optional.of(version));
        when(versions.findGradeThreshold(1L, SceneType.FESTIVAL.name()))
                .thenReturn(Optional.of(threshold));
        when(alerts.existsByProductIdAndRiskTypeAndStatus(
                        104L, "DATA_INSUFFICIENT", com.example.ssds.core.domain.AlertStatus.OPEN))
                .thenReturn(false);

        SceneClassificationResponse classification = new SceneClassificationResponse(
                11L, 104L, SceneCode.FESTIVAL, new BigDecimal("0.82"), "節慶匹配",
                null, List.of(), false, null, false, "HIGH", "model",
                "MODEL_CLASSIFY", "v3", null, null, 0, null);

        ScoreRecalculationService.Result result = new ScoreRecalculationService(
                scores, versions, products, initialScoring, alerts)
                .recalculate(104L, classification);

        assertEquals(LastScoringStatus.INSUFFICIENT_DATA, result.status());
        assertTrue(result.scores().isEmpty());
        assertEquals(LastScoringStatus.INSUFFICIENT_DATA, product.getLastScoringStatus());
        assertNotNull(product.getLastScoringAttemptedAt());
        verify(products).save(product);
        verify(alerts).save(argThat(alert ->
                "DATA_INSUFFICIENT".equals(alert.getRiskType())
                        && alert.getProduct() == product));
        verify(scores, never()).saveAll(anyList());
        verify(scores, never()).deactivateCurrentScores(anyLong(), anyString());
    }

    private static ProductScore sourceScore(Product product) {
        ProductScore score = ProductScore.builder()
                .product(product)
                .sceneType(SceneType.REPLENISHMENT)
                .primary(true)
                .active(true)
                .bonusSubtotal(new BigDecimal("80"))
                .penaltySubtotal(BigDecimal.ZERO)
                .finalScore(new BigDecimal("80"))
                .grade(Grade.A)
                .confidence(100)
                .build();
        List<ScoreFactor> factors = new ArrayList<>();
        for (FactorCode code : FactorCode.values()) {
            boolean penalty = code.isPenalty();
            factors.add(ScoreFactor.builder()
                    .score(score)
                    .factorCode(code)
                    .normalizedValue(penalty ? null : new BigDecimal("80"))
                    .weight(penalty ? null : new BigDecimal("0.167"))
                    .penaltyValue(penalty ? BigDecimal.ZERO : null)
                    .penalty(penalty)
                    .dataAvailable(true)
                    .build());
        }
        score.setFactors(factors);
        return score;
    }

    private static ProductScore insufficientSource(Product product) {
        ProductScore score = ProductScore.builder()
                .product(product)
                .sceneType(SceneType.REPLENISHMENT)
                .primary(true)
                .active(true)
                .confidence(60)
                .build();
        List<ScoreFactor> factors = new ArrayList<>();
        for (FactorCode code : FactorCode.values()) {
            boolean margin = code == FactorCode.MARGIN;
            factors.add(ScoreFactor.builder()
                    .score(score)
                    .factorCode(code)
                    .normalizedValue(margin ? new BigDecimal("70") : null)
                    .penaltyValue(code.isPenalty() ? BigDecimal.ZERO : null)
                    .penalty(code.isPenalty())
                    .dataAvailable(margin)
                    .build());
        }
        score.setFactors(factors);
        return score;
    }

    private static WeightVersion versionWithProfiles() {
        WeightVersion version = WeightVersion.builder().id(1L).build();
        List<WeightProfile> profiles = new ArrayList<>();
        for (SceneType scene : List.of(SceneType.FESTIVAL, SceneType.SEASONAL)) {
            for (FactorCode code : FactorCode.values()) {
                if (!code.isPenalty()) {
                    profiles.add(WeightProfile.builder()
                            .version(version)
                            .sceneType(scene)
                            .factorCode(code)
                            .weight(switch (code) {
                                case TREND -> new BigDecimal("0.18");
                                case MARGIN -> new BigDecimal("0.18");
                                case CVR -> new BigDecimal("0.17");
                                case PRICE_FIT -> new BigDecimal("0.07");
                                case FESTIVAL -> new BigDecimal("0.30");
                                case CLIMATE -> new BigDecimal("0.10");
                                default -> throw new IllegalStateException();
                            })
                            .build());
                }
            }
        }
        version.setProfiles(profiles);
        return version;
    }
}
