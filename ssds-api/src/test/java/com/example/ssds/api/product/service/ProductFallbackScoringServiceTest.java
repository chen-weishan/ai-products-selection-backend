package com.example.ssds.api.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.dao.ProductMarginStatisticsDao;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductFallbackScoringServiceTest {

    private WeightVersionRepository weightVersionRepository;
    private ProductScoreRepository scoreRepository;
    private ProductMarginStatisticsDao marginStatisticsDao;
    private ProductFallbackScoringService service;
    private Product product;

    @BeforeEach
    void setUp() {
        weightVersionRepository = mock(WeightVersionRepository.class);
        scoreRepository = mock(ProductScoreRepository.class);
        marginStatisticsDao = mock(ProductMarginStatisticsDao.class);
        service = new ProductFallbackScoringService(
                weightVersionRepository,
                scoreRepository,
                marginStatisticsDao
        );
        product = Product.builder()
                .id(10L)
                .name("測試品項")
                .category(Category.builder().id(20L).build())
                .trackType(TrackType.A)
                .marginRate(new BigDecimal("35.00"))
                .season(Season.ALL)
                .build();

        when(weightVersionRepository.findByIsCurrentTrue())
                .thenReturn(Optional.of(WeightVersion.builder().id(1L).build()));
    }

    @Test
    void missingMarginDataThrowsInsufficientDataException() {
        when(marginStatisticsDao.findPercentile(10L, 20L))
                .thenReturn(Optional.empty());

        assertThrows(InsufficientDataException.class, () -> service.score(product));
    }

    @Test
    void buildScoreUsesReplenishmentByDefaultWithoutWriting() {
        stubScoringData(SceneType.REPLENISHMENT);

        ProductScore score = service.buildScore(product);

        assertThat(score.getSceneType()).isEqualTo(SceneType.REPLENISHMENT);
        verifyNoInteractions(scoreRepository);
    }

    @Test
    void buildScoreKeepsSpecifiedSceneWithoutWriting() {
        stubScoringData(SceneType.FESTIVAL);

        ProductScore score = service.buildScore(product, SceneType.FESTIVAL);

        assertThat(score.getSceneType()).isEqualTo(SceneType.FESTIVAL);
        verify(marginStatisticsDao).findGradeThreshold(1L, SceneType.FESTIVAL.name());
        verifyNoInteractions(scoreRepository);
    }

    @Test
    void scoreDeactivatesAndSavesOnlyTheSpecifiedScene() {
        stubScoringData(SceneType.SEASONAL);
        when(scoreRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(ProductScore.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductScore saved = service.score(product, SceneType.SEASONAL);

        verify(scoreRepository).deactivateCurrent(10L, saved.getPeriod(), SceneType.SEASONAL);
        verify(scoreRepository).saveAndFlush(saved);
        verify(scoreRepository, never()).deactivateCurrent(10L, saved.getPeriod(), SceneType.REPLENISHMENT);
        assertThat(saved.getSceneType()).isEqualTo(SceneType.SEASONAL);
    }

    private void stubScoringData(SceneType scene) {
        when(marginStatisticsDao.findPercentile(10L, 20L))
                .thenReturn(Optional.of(new ProductMarginStatisticsDao.MarginPercentile(
                        new BigDecimal("75.00"), 20L, false)));
        when(marginStatisticsDao.findGradeThreshold(1L, scene.name()))
                .thenReturn(Optional.of(new ProductMarginStatisticsDao.GradeThreshold(
                        new BigDecimal("80.00"), new BigDecimal("65.00"))));
    }
}
