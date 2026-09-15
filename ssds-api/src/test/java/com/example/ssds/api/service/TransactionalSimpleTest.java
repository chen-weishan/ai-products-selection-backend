package com.example.ssds.api.service;

import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import jakarta.transaction.Transactional;

@SpringBootTest
@Transactional
class TransactionalSimpleTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private WeightVersionRepository weightVersionRepository;

    @Autowired
    private ProductScoreRepository productScoreRepository;

    @Test
    void testCategorySave() {
        Category category = Category.builder()
                .name("Simple Test Category")
                .build();
        Category saved = categoryRepository.save(category);
        Assertions.assertNotNull(saved.getId());
    }

    @Test
    void testProductSave() {
        Category category = Category.builder()
                .name("Product Test Category")
                .build();
        category = categoryRepository.save(category);

        Product product = Product.builder()
                .name("Simple Test Product")
                .category(category)
                .trackType(com.example.ssds.core.domain.TrackType.A)
                .build();
        Product saved = productRepository.save(product);
        Assertions.assertNotNull(saved.getId());
    }

    @Test
    void testWeightVersionSave() {
        WeightVersion wv = WeightVersion.builder()
                .versionNo("2.0")
                .name("Test Weight Version")
                .effectiveFrom(java.time.LocalDate.of(2026, 1, 1))
                .build();
        WeightVersion saved = weightVersionRepository.save(wv);
        Assertions.assertNotNull(saved.getId());
    }

    @Test
    void testProductScoreSave() {
        // Create dependencies
        Category category = Category.builder()
                .name("ProductScore Test Category")
                .build();
        category = categoryRepository.save(category);

        Product product = Product.builder()
                .name("ProductScore Test Product")
                .category(category)
                .trackType(com.example.ssds.core.domain.TrackType.A)
                .build();
        product = productRepository.save(product);

        WeightVersion weightVersion = WeightVersion.builder()
                .versionNo("3.0")
                .name("ProductScore Test Weight Version")
                .effectiveFrom(java.time.LocalDate.of(2026, 1, 1))
                .build();
        weightVersion = weightVersionRepository.save(weightVersion);

        // Create ProductScore
        com.example.ssds.infra.entity.ProductScore score = com.example.ssds.infra.entity.ProductScore.builder()
                .product(product)
                .weightVersion(weightVersion)
                .period("2026W30")
                .sceneType(com.example.ssds.core.domain.SceneType.VIRAL)
                .grade(com.example.ssds.core.domain.Grade.A)
                .bonusSubtotal(new java.math.BigDecimal("80"))
                .penaltySubtotal(java.math.BigDecimal.ZERO)
                .finalScore(new java.math.BigDecimal("80"))
                .active(true)
                .primary(true)
                .calculatedAt(java.time.Instant.now())
                .confidence(100)
                .build();
        com.example.ssds.infra.entity.ProductScore saved = productScoreRepository.save(score);
        Assertions.assertNotNull(saved.getId());
    }
}
