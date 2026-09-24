package com.example.ssds.api.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ssds.ai.access.tracka.AiClientResponse;
import com.example.ssds.ai.access.tracka.TrackAAiClient;
import com.example.ssds.ai.policy.ExternalLlmDisabledException;
import com.example.ssds.ai.policy.ExternalLlmPolicy;
import com.example.ssds.api.aitask.execution.AiTaskCreatedEvent;
import com.example.ssds.api.aitask.execution.AiTaskWorker;
import com.example.ssds.api.recommendation.RecommendationService;
import com.example.ssds.api.scoring.ScoreEvaluationService.FactorInput;
import com.example.ssds.api.scoring.factor.ScoringFactorBatchService;
import com.example.ssds.api.scoring.factor.ScoringFactorBatchService.PreparedPopulation;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.InsightType;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.core.domain.WeightVersionStatus;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.entity.AiTaskItem;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductReview;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.WeightProfile;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.AiInsightRepository;
import com.example.ssds.infra.repository.AiTaskItemRepository;
import com.example.ssds.infra.repository.AiTaskRepository;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductReviewRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.ScoreFactorRepository;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;
import com.example.ssds.infra.repository.WeightProfileRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** FULL_ANALYSIS 的資料庫 E2E：Agent 全面降級不得回滾正式評分快照。 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate",
        "ai.full-analysis.schedule-enabled=false",
        "ai.trend.schedule-enabled=false",
        "ai.calibration.schedule-enabled=false",
        "ai.retry-max=0",
        "mistral.model-classify-fallbacks=",
        "mistral.model-long-text-fallbacks=",
        "mistral.model-short-gen-fallbacks="
})
@Transactional
class FullAnalysisFailureEndToEndTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.6-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @MockitoBean
    private TrackAAiClient trackAAiClient;
    @MockitoBean
    private ExternalLlmPolicy externalLlmPolicy;
    @MockitoBean
    private ScoringFactorBatchService factorBatchService;

    @Autowired
    private AiTaskWorker worker;
    @Autowired
    private AiTaskRepository tasks;
    @Autowired
    private AiTaskItemRepository taskItems;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private ProductRepository products;
    @Autowired
    private ProductReviewRepository productReviews;
    @Autowired
    private ProductScoreRepository scores;
    @Autowired
    private ScoreFactorRepository scoreFactors;
    @Autowired
    private SceneClassificationLogRepository sceneLogs;
    @Autowired
    private AiInsightRepository insights;
    @Autowired
    private RecommendationService recommendationService;
    @Autowired
    private WeightVersionRepository versions;
    @Autowired
    private WeightProfileRepository profiles;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUpScoringAuthority() {
        WeightVersion version = versions.saveAndFlush(WeightVersion.builder()
                .versionNo("e2e-" + UUID.randomUUID().toString().substring(0, 8))
                .name("FULL_ANALYSIS failure E2E weights")
                .status(WeightVersionStatus.DRAFT)
                .isCurrent(true)
                .build());
        for (SceneType scene : SceneType.values()) {
            List<WeightProfile> sceneProfiles = profileRows(version, scene);
            version.getProfiles().addAll(sceneProfiles);
            profiles.saveAll(sceneProfiles);
            jdbc.sql("""
                    insert into grade_threshold
                        (version_id, scene_type, grade_a_min, grade_b_min)
                    values (:versionId, :scene, 80.00, 65.00)
                    """)
                    .param("versionId", version.getId())
                    .param("scene", scene.name())
                    .update();
        }
        when(factorBatchService.preparePopulation(any(), any())).thenAnswer(invocation -> {
            List<Product> population = invocation.getArgument(0);
            java.time.LocalDate evaluationDate = invocation.getArgument(1);
            Map<Long, Map<FactorCode, FactorInput>> result = new java.util.LinkedHashMap<>();
            population.forEach(product -> result.put(product.getId(), completeFactors()));
            Map<Long, Product> productsById = population.stream().collect(
                    java.util.stream.Collectors.toMap(Product::getId, product -> product));
            return new PreparedPopulation(
                    evaluationDate, productsById, Map.of(), Map.copyOf(result));
        });
        when(factorBatchService.refreshTarget(any(), anyLong())).thenReturn(completeFactors());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("agentFailureScenarios")
    @DisplayName("FULL_ANALYSIS keeps a formal score when every Agent falls back")
    void keepsFormalScoreWhenEveryAgentFallsBack(
            String scenario, FailureMode failureMode, String expectedFallbackReason) {
        configureFailure(failureMode);
        Product product = newProduct(scenario);
        addReviews(product);
        AiTask task = tasks.saveAndFlush(AiTask.builder()
                .taskType(AiTaskType.FULL_ANALYSIS)
                .budgetPool(AiTaskType.BudgetPool.TRACK_A)
                .status(TaskStatus.PENDING)
                .totalCount(1)
                .build());
        taskItems.saveAndFlush(AiTaskItem.builder().task(task).product(product).build());

        AiTaskWorker synchronousWorker = AopTestUtils.getTargetObject(worker);
        synchronousWorker.run(new AiTaskCreatedEvent(task.getId(), true));
        entityManager.flush();
        entityManager.clear();

        AiTask reloadedTask = tasks.findById(task.getId()).orElseThrow();
        AiTaskItem reloadedItem = taskItems.findByTaskId(task.getId()).getFirst();
        List<ProductScore> history = scores.findByProductIdOrderByCalculatedAtDesc(product.getId());
        List<ProductScore> activePrimary = history.stream()
                .filter(ProductScore::isActive)
                .filter(ProductScore::isPrimary)
                .toList();
        var sceneLog = sceneLogs.findFirstByProductIdOrderByCreatedAtDesc(product.getId())
                .orElseThrow(() -> new AssertionError(
                        "scene log missing; item=" + reloadedItem.getStatus()
                                + ", error=" + reloadedItem.getErrorMessage()
                                + ", scores=" + history.size()));
        var recommendation = recommendationService.latest(product.getId());

        assertAll(
                () -> assertFalse(history.isEmpty()),
                () -> assertEquals(1, activePrimary.size()),
                () -> assertEquals(9, scoreFactors.findByScoreId(
                        activePrimary.getFirst().getId()).size()),
                () -> assertEquals(expectedFallbackReason, sceneLog.getFallbackReason()),
                () -> assertTrue(sceneLog.isFallbackApplied()),
                () -> assertTrue(insights.findByProductIdAndInsightTypeAndCurrentTrue(
                        product.getId(), InsightType.RECOMMENDATION).isPresent(),
                        reloadedItem.getErrorMessage()),
                () -> assertTrue(recommendation.fallbackApplied()),
                () -> assertEquals("RULE_FALLBACK", recommendation.fallbackReason()),
                () -> assertEquals("rule-fallback", recommendation.model()),
                () -> assertTrue(recommendation.action() != null),
                () -> assertFalse(recommendation.quantityText().isBlank()),
                () -> assertFalse(recommendation.reasoning().isBlank()),
                () -> assertEquals(TaskItemStatus.FAILED, reloadedItem.getStatus()),
                () -> assertEquals(TaskStatus.FAILED, reloadedTask.getStatus()),
                () -> assertTrue(reloadedItem.getErrorMessage().contains("情境判定已使用 REPLENISHMENT 降級值"),
                        reloadedItem.getErrorMessage()));

        if (failureMode == FailureMode.DISABLED) {
            verify(externalLlmPolicy, atLeast(4)).validateUserJson(any());
            verifyNoInteractions(trackAAiClient);
        } else {
            verify(trackAAiClient, atLeast(4)).complete(any());
        }
    }

    private void configureFailure(FailureMode mode) {
        switch (mode) {
            case DISABLED -> doThrow(new ExternalLlmDisabledException("外部 AI 已依政策停用"))
                    .when(externalLlmPolicy).validateUserJson(any());
            case TIMEOUT -> when(trackAAiClient.complete(any()))
                    .thenThrow(new ResourceAccessException("timeout"));
            case SCHEMA_INVALID -> when(trackAAiClient.complete(any()))
                    .thenReturn(new AiClientResponse("{}", "schema-invalid-model", 1, 1));
        }
    }

    private Product newProduct(String suffix) {
        Category category = categories.save(Category.builder()
                .name("E2E-" + UUID.randomUUID().toString().substring(0, 8))
                .build());
        return products.saveAndFlush(Product.builder()
                .name("FULL E2E product " + suffix)
                .category(category)
                .trackType(TrackType.A)
                .status(ProductStatus.EVALUATING)
                .cost(new BigDecimal("60.00"))
                .suggestedPrice(new BigDecimal("100.00"))
                .moq(10)
                .build());
    }

    private void addReviews(Product product) {
        for (int index = 0; index < 20; index++) {
            productReviews.save(ProductReview.builder()
                    .product(product)
                    .source("full-e2e")
                    .content("整合測試評論 " + index + "：品質與包裝皆需由 Agent 分析")
                    .contentHash(UUID.randomUUID().toString().replace("-", ""))
                    .reviewedAt(LocalDate.of(2026, 9, 1).plusDays(index))
                    .build());
        }
        productReviews.flush();
    }

    private static Map<FactorCode, FactorInput> completeFactors() {
        EnumMap<FactorCode, FactorInput> factors = new EnumMap<>(FactorCode.class);
        for (FactorCode code : FactorCode.values()) {
            factors.put(code, code.isPenalty()
                    ? new FactorInput(BigDecimal.ZERO, null, BigDecimal.ZERO, true, false, "E2E risk")
                    : new FactorInput(BigDecimal.ONE, new BigDecimal("80"), null, true, false, "E2E evidence"));
        }
        return factors;
    }

    private static List<WeightProfile> profileRows(WeightVersion version, SceneType scene) {
        Map<FactorCode, BigDecimal> weights = Map.of(
                FactorCode.TREND, new BigDecimal("0.200"),
                FactorCode.MARGIN, new BigDecimal("0.200"),
                FactorCode.CVR, new BigDecimal("0.200"),
                FactorCode.PRICE_FIT, new BigDecimal("0.100"),
                FactorCode.FESTIVAL, new BigDecimal("0.200"),
                FactorCode.CLIMATE, new BigDecimal("0.100"));
        return weights.entrySet().stream()
                .map(entry -> WeightProfile.builder()
                        .version(version)
                        .sceneType(scene)
                        .factorCode(entry.getKey())
                        .weight(entry.getValue())
                        .build())
                .toList();
    }

    private static Stream<Arguments> agentFailureScenarios() {
        return Stream.of(
                Arguments.of("AI 全停用", FailureMode.DISABLED, "AI_UNAVAILABLE"),
                Arguments.of("AI 全 timeout", FailureMode.TIMEOUT, "AI_UNAVAILABLE"),
                Arguments.of("AI Schema 全失敗", FailureMode.SCHEMA_INVALID, "SCHEMA_INVALID"));
    }

    private enum FailureMode {
        DISABLED,
        TIMEOUT,
        SCHEMA_INVALID
    }
}
