package com.example.ssds.api.service;

import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import java.util.UUID;

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

    /**
     * 唯一的版本號。
     *
     * <p>不可寫死成 "2.0"：{@code weight_version.version_no} 有唯一鍵，而本測試連的是
     * 共用資料庫。只要團隊中任何人在沒有 {@code @Transactional} 的舊分支上跑過一次
     * 測試（2026-09-14 就發生過），那筆列就會留在庫裡，之後<b>所有人</b>的這支測試
     * 都會撞唯一鍵而失敗，且失敗訊息與程式碼邏輯完全無關，很難查。
     *
     * <p>@Transactional 只能保證「自己不留垃圾」，擋不住別人留下的；用唯一值才能讓
     * 這支測試不依賴共用庫當下是否乾淨。長度受限於 VARCHAR(16)。
     */
    private static String uniqueVersionNo() {
        // version_no 是 VARCHAR(16)，完整 UUID（36 字元）塞不下。
        // 取 12 個十六進位字元 + 前綴共 13 字元，仍在上限內。
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }


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
                .versionNo(uniqueVersionNo())
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
                .versionNo(uniqueVersionNo())
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
