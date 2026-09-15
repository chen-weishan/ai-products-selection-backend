package com.example.ssds.api.service;

import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import jakarta.transaction.Transactional;

/**
 * Repository 存得進去、拿得回 id。
 *
 * <p><b>@Transactional 不可拿掉</b>：本測試連的是共用的 Supabase 資料庫，
 * 沒有回滾的話每跑一次就在 category／product／weight_version 留下一組
 * 「Simple Test ...」的資料。2026-09-11 清出 28 筆這種殘留，
 * 而且 {@code version_no = "2.0"} 的唯一鍵會撞到前一次自己留下的列，
 * 讓測試從第二次起就固定失敗。
 *
 * <p><b>與 {@link TransactionalSimpleTest} 內容重複</b>：那支是本檔加上
 * {@code @Transactional} 的版本。兩支現在完全等價，應該擇一刪除——
 * 保留哪一支由 PR 作者決定，這裡先止血不擅自刪別人的測試。
 *
 * <p>Flyway 護欄的理由見 {@code build.gradle} 的 test 設定。
 */
@TestPropertySource(properties = "spring.flyway.enabled=false")
@SpringBootTest
@Transactional
class SimpleTest {

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
