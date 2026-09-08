package com.example.ssds.api.scoring;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ssds.ai.model.SceneCode;
import com.example.ssds.api.scene.dto.SceneClassificationResponse;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.ProductScoreRepository;
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
                null);

        List<ProductScore> result = new ScoreRecalculationService(scores, versions)
                .recalculate(102L, classification);

        assertEquals(2, result.size());
        assertEquals(SceneType.FESTIVAL, result.get(0).getSceneType());
        assertTrue(result.get(0).isPrimary());
        assertEquals(SceneType.SEASONAL, result.get(1).getSceneType());
        assertFalse(result.get(1).isPrimary());
        assertEquals(9, result.get(0).getFactors().size());
        verify(scores).deactivateCurrentScores(eq(102L), matches("\\d{4}W\\d{2}"));
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
