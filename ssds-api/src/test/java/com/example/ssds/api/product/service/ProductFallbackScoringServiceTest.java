package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.dao.ProductMarginStatisticsDao;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductFallbackScoringServiceTest {

    @Test
    void missingMarginDataThrowsInsufficientDataException() {
        WeightVersionRepository weightVersionRepository = mock(WeightVersionRepository.class);
        ProductScoreRepository scoreRepository = mock(ProductScoreRepository.class);
        ProductMarginStatisticsDao marginStatisticsDao = mock(ProductMarginStatisticsDao.class);
        ProductFallbackScoringService service = new ProductFallbackScoringService(
                weightVersionRepository,
                scoreRepository,
                marginStatisticsDao
        );
        Product product = Product.builder()
                .id(10L)
                .name("測試品項")
                .category(Category.builder().id(20L).build())
                .trackType(TrackType.A)
                .build();

        when(weightVersionRepository.findByIsCurrentTrue())
                .thenReturn(Optional.of(WeightVersion.builder().id(1L).build()));
        when(marginStatisticsDao.findPercentile(10L, 20L))
                .thenReturn(Optional.empty());

        assertThrows(InsufficientDataException.class, () -> service.score(product));
    }
}
