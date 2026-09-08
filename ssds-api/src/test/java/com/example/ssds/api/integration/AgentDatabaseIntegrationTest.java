package com.example.ssds.api.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.ssds.ai.agent.ProductInsightAgent;
import com.example.ssds.ai.agent.RecommendationAgent;
import com.example.ssds.ai.agent.ReviewRiskAgent;
import com.example.ssds.ai.agent.SourcingScoutAgent;
import com.example.ssds.ai.agent.WeightCalibrationAgent;
import com.example.ssds.ai.model.ProductInsightOutput;
import com.example.ssds.ai.model.ProductInsightResult;
import com.example.ssds.ai.model.ProductInsightRisk;
import com.example.ssds.ai.model.RecommendationOutput;
import com.example.ssds.ai.model.RecommendationResult;
import com.example.ssds.ai.model.ReviewRiskAnalysis;
import com.example.ssds.ai.model.ReviewRiskOutput;
import com.example.ssds.ai.model.ReviewRiskResult;
import com.example.ssds.ai.model.SellingPoint;
import com.example.ssds.ai.model.SourcingScoutOutput;
import com.example.ssds.ai.model.SourcingScoutResult;
import com.example.ssds.ai.model.WeightCalibrationOutput;
import com.example.ssds.ai.model.WeightCalibrationResult;
import com.example.ssds.api.calibration.WeightCalibrationService;
import com.example.ssds.api.calibration.dto.WeightCalibrationInterpretRequest;
import com.example.ssds.api.insight.ProductInsightService;
import com.example.ssds.api.recommendation.RecommendationService;
import com.example.ssds.api.review.ReviewRiskService;
import com.example.ssds.api.sourcing.SourcingScoutService;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.CalibrationStatus;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.InsightRiskType;
import com.example.ssds.core.domain.InsightType;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.Sentiment;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.core.domain.UserStatus;
import com.example.ssds.core.domain.WeightVersionStatus;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.CalibrationReport;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.DecisionRecord;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductReview;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.entity.TrendInterpretation;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.entity.WeightProfile;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.AiTaskRepository;
import com.example.ssds.infra.repository.AiInsightRepository;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.CalibrationReportRepository;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.DecisionRecordRepository;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductReviewRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.ReviewAnalysisRepository;
import com.example.ssds.infra.repository.ScoreFactorRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
import com.example.ssds.infra.repository.TrendInterpretationRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.infra.repository.WeightProfileRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Report 3.2: integration checks against a clean V1-to-latest PostgreSQL schema. */
@Testcontainers
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate",
        "ai.full-analysis.schedule-enabled=false",
        "ai.trend.schedule-enabled=false",
        "ai.calibration.schedule-enabled=false"
})
@Transactional
class AgentDatabaseIntegrationTest {

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
    private SourcingScoutAgent sourcingScoutAgent;
    @MockitoBean
    private ReviewRiskAgent reviewRiskAgent;
    @MockitoBean
    private ProductInsightAgent productInsightAgent;
    @MockitoBean
    private RecommendationAgent recommendationAgent;
    @MockitoBean
    private WeightCalibrationAgent weightCalibrationAgent;

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private AiTaskRepository aiTasks;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private TrendKeywordRepository keywords;
    @Autowired
    private ProductRepository products;
    @Autowired
    private ProductReviewRepository productReviews;
    @Autowired
    private ReviewAnalysisRepository reviewAnalyses;
    @Autowired
    private ProductScoreRepository productScores;
    @Autowired
    private ScoreFactorRepository scoreFactors;
    @Autowired
    private DecisionRecordRepository decisions;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private AiInsightRepository insights;
    @Autowired
    private WeightVersionRepository weightVersions;
    @Autowired
    private WeightProfileRepository weightProfiles;
    @Autowired
    private CalibrationReportRepository calibrationReports;
    @Autowired
    private HeatCompositeDailyRepository heatComposites;
    @Autowired
    private TrendInterpretationRepository trends;
    @Autowired
    private SourcingCandidateRepository candidates;
    @Autowired
    private SourcingScoutService sourcingScoutService;
    @Autowired
    private ReviewRiskService reviewRiskService;
    @Autowired
    private ProductInsightService productInsightService;
    @Autowired
    private RecommendationService recommendationService;
    @Autowired
    private WeightCalibrationService weightCalibrationService;

    @Test
    @DisplayName("Agent 2: stores review analysis without writing scores, factors, or decisions")
    void agent2DoesNotWriteScoringOrDecisionAuthority() {
        AuthorityFixture fixture = authorityFixture("agent2");
        ProductReview review = productReviews.saveAndFlush(ProductReview.builder()
                .product(fixture.product())
                .source("integration-test")
                .content("包裝完整，品質穩定")
                .contentHash("agent2-review-hash")
                .reviewedAt(LocalDate.of(2026, 9, 1))
                .build());
        String authorityBefore = scoringAndDecisionSnapshot();

        when(reviewRiskAgent.analyze(any(), anyInt(), any(), eq(false))).thenReturn(
                new ReviewRiskResult(
                        new ReviewRiskOutput(
                                List.of(new ReviewRiskAnalysis(
                                        review.getId(), Sentiment.POSITIVE, null)),
                                List.of()),
                        false, null, false, "review-test-model", "review-risk-v1"));

        reviewRiskService.analyze(fixture.product().getId(), false);
        entityManager.flush();
        entityManager.clear();

        assertAll(
                () -> assertEquals(1, reviewAnalyses.count()),
                () -> assertEquals(
                        Sentiment.POSITIVE,
                        reviewAnalyses.findById(review.getId()).orElseThrow().getSentiment()),
                () -> assertEquals(authorityBefore, scoringAndDecisionSnapshot()));
    }

    @Test
    @DisplayName("Agent 3: stores selling-point and risk insights without writing authority tables")
    void agent3DoesNotWriteScoringOrDecisionAuthority() {
        AuthorityFixture fixture = authorityFixture("agent3");
        productReviews.saveAndFlush(ProductReview.builder()
                .product(fixture.product())
                .source("integration-test")
                .content("口味清爽，但外箱偶有凹損")
                .contentHash("agent3-review-hash")
                .reviewedAt(LocalDate.of(2026, 9, 1))
                .build());
        String authorityBefore = scoringAndDecisionSnapshot();

        when(productInsightAgent.analyze(any(), anyInt(), any(), eq(false))).thenReturn(
                new ProductInsightResult(
                        new ProductInsightOutput(
                                List.of(
                                        new SellingPoint("評論提到口味清爽", 1, "口味"),
                                        new SellingPoint("包裝適合陳列", 1, "包裝")),
                                List.of(
                                        new ProductInsightRisk(
                                                "外箱可能凹損", 1, InsightRiskType.LOGISTICS,
                                                Severity.MEDIUM, true),
                                        new ProductInsightRisk(
                                                "評論樣本仍有限", 1, InsightRiskType.OTHER,
                                                Severity.LOW, false))),
                        false, null, false, "insight-test-model", "product-insight-v2",
                        20, 10, 1));

        productInsightService.analyze(fixture.product().getId(), false);
        entityManager.flush();
        entityManager.clear();

        assertAll(
                () -> assertEquals(
                        Set.of(InsightType.SELLING_POINT, InsightType.RISK),
                        insights.findByProductIdAndCurrentTrue(fixture.product().getId()).stream()
                                .map(value -> value.getInsightType())
                                .collect(java.util.stream.Collectors.toSet())),
                () -> assertEquals(authorityBefore, scoringAndDecisionSnapshot()));
    }

    @Test
    @DisplayName("Agent 4: stores recommendation insight without creating or changing decisions")
    void agent4DoesNotWriteScoringOrDecisionAuthority() {
        AuthorityFixture fixture = authorityFixture("agent4");
        String authorityBefore = scoringAndDecisionSnapshot();

        when(recommendationAgent.recommend(any(), eq(false))).thenReturn(
                new RecommendationResult(
                        new RecommendationOutput(
                                DecisionType.WATCH, 0, 0, "暫不建立首批數量",
                                "依既有分級與扣分結果，建議持續觀察。"),
                        false, null, false, "recommendation-test-model", "recommendation-v1",
                        20, 10, 1));

        recommendationService.recommend(fixture.product().getId(), false);
        entityManager.flush();
        entityManager.clear();

        assertAll(
                () -> assertEquals(
                        InsightType.RECOMMENDATION,
                        insights.findByProductIdAndInsightTypeAndCurrentTrue(
                                        fixture.product().getId(), InsightType.RECOMMENDATION)
                                .orElseThrow()
                                .getInsightType()),
                () -> assertEquals(authorityBefore, scoringAndDecisionSnapshot()));
    }

    @Test
    @DisplayName("Agent 7: updates only interpretation fields and cannot change weight authority")
    void agent7DoesNotWriteWeightOrCalibrationAuthority() {
        AuthorityFixture fixture = authorityFixture("agent7");
        CalibrationReport report = calibrationReports.saveAndFlush(CalibrationReport.builder()
                .quarter("2026Q3")
                .sampleSize(240)
                .regressionResult("{\"method\":\"pearson\",\"factors\":[{\"code\":\"TREND\",\"correlation\":0.71,\"currentWeight\":0.50,\"suggestedWeight\":0.47,\"pValue\":0.11}]}")
                .backtestResult("{\"backtests\":[{\"scheme\":\"CURRENT\",\"correlation\":0.63,\"gradeAHitRate\":0.67}]}")
                .acceptedItems("[\"TREND\"]")
                .status(CalibrationStatus.PENDING)
                .build());
        String weightBefore = weightAuthoritySnapshot();
        String protectedReportBefore = protectedCalibrationSnapshot(report.getId());

        when(weightCalibrationAgent.interpret(any(), eq(false))).thenReturn(
                new WeightCalibrationResult(
                        new WeightCalibrationOutput(
                                "統計結果顯示趨勢因子可持續觀察。",
                                List.of(new WeightCalibrationOutput.AdjustmentAdvice(
                                        "TREND", "依統計模組建議方向進行人工審核")),
                                List.of("注意樣本代表性")),
                        false, null, false, "calibration-test-model", "calibration-v1",
                        20, 10, 1));
        WeightCalibrationInterpretRequest request = new WeightCalibrationInterpretRequest(
                new WeightCalibrationInterpretRequest.SceneOverrideStatistics(
                        240, 24, new BigDecimal("0.10"), List.of()),
                false);

        weightCalibrationService.interpret(report.getId(), request);
        entityManager.flush();
        entityManager.clear();

        CalibrationReport reloaded = calibrationReports.findById(report.getId()).orElseThrow();
        assertAll(
                () -> assertEquals("統計結果顯示趨勢因子可持續觀察。", reloaded.getAiInterpretation()),
                () -> assertEquals("calibration-v1", reloaded.getPromptVersion()),
                () -> assertEquals(weightBefore, weightAuthoritySnapshot()),
                () -> assertEquals(protectedReportBefore, protectedCalibrationSnapshot(report.getId())));
    }

    @Test
    @DisplayName("V21: one flush can increase total and retry request counts together")
    void v21AllowsAtomicRequestAndRetryCountUpdate() {
        AiTask task = aiTasks.saveAndFlush(AiTask.builder()
                .taskType(AiTaskType.FULL_ANALYSIS)
                .budgetPool(AiTaskType.BudgetPool.TRACK_A)
                .status(TaskStatus.RUNNING)
                .totalCount(1)
                .startedAt(Instant.now())
                .build());

        task.setRequestCount(3);
        task.setRetryPoolRequestCount(3);
        aiTasks.saveAndFlush(task);
        entityManager.clear();

        AiTask reloaded = aiTasks.findById(task.getId()).orElseThrow();
        assertAll(
                () -> assertEquals(3, reloaded.getRequestCount()),
                () -> assertEquals(3, reloaded.getRetryPoolRequestCount()));
    }

    @Test
    @DisplayName("Agent 6: only stores its report and cannot overwrite time-gap authority fields")
    void agent6DoesNotOverwriteDrivingKeywordOrTimeGap() {
        Category category = categories.save(Category.builder().name("測試品類").build());
        TrendKeyword keyword = keywords.save(TrendKeyword.builder().keyword("DB整合測試關鍵字").build());
        Product product = products.save(Product.builder()
                .name("DB整合測試商品")
                .category(category)
                .trackType(TrackType.B)
                .status(ProductStatus.DRAFT)
                .sourcingStatus(SourcingStatus.PENDING)
                .keywords(new LinkedHashSet<>(Set.of(keyword)))
                .build());

        heatComposites.save(HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(LocalDate.of(2026, 8, 22))
                .compositeValue(new BigDecimal("70.00"))
                .stage(HeatStage.RISING)
                .stageWeeks((short) 1)
                .estimatedLifespanDays(56)
                .appliedWeights("{}")
                .build());
        heatComposites.save(HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(LocalDate.of(2026, 8, 23))
                .compositeValue(new BigDecimal("65.00"))
                .stage(HeatStage.PLATEAU)
                .stageWeeks((short) 4)
                .estimatedLifespanDays(35)
                .appliedWeights("{}")
                .build());

        TrendInterpretation conflictingTrend = trends.save(TrendInterpretation.builder()
                .keyword(keyword)
                .heatStage(HeatStage.RISING)
                .stageWeeks((short) 3)
                .estimatedLifespanDays(56)
                .inputSnapshot("{}")
                .fallbackApplied(false)
                .model("history-model")
                .promptVersion("trend-v1")
                .generatedAt(Instant.now())
                .current(true)
                .build());
        SourcingCandidate candidate = candidates.saveAndFlush(SourcingCandidate.builder()
                .product(product)
                .keyword(keyword)
                .category(category)
                .leadTimeDays(20)
                .drivingKeyword(keyword)
                .timeGapDays(15)
                .trendInterpretation(conflictingTrend)
                .build());
        entityManager.clear();

        when(sourcingScoutAgent.scout(any(), eq(false))).thenReturn(new SourcingScoutResult(
                new SourcingScoutOutput(
                        "探索報告內容必須至少二十個字元以符合驗證", List.of("機會訊號"), List.of("風險訊號")),
                false, "scout-test-model", "scout-v6", 10, 5, 1));

        sourcingScoutService.scout(product.getId(), false);
        entityManager.flush();
        entityManager.clear();

        SourcingCandidate reloaded = candidates.findDetailedByProductId(product.getId()).orElseThrow();
        assertAll(
                () -> assertEquals(keyword.getId(), reloaded.getDrivingKeyword().getId()),
                () -> assertEquals(15, reloaded.getTimeGapDays()),
                () -> assertEquals(SourcingStatus.PENDING, reloaded.getProduct().getSourcingStatus()),
                () -> assertEquals(conflictingTrend.getId(), reloaded.getTrendInterpretation().getId()));
    }

    private AuthorityFixture authorityFixture(String suffix) {
        Category category = categories.save(Category.builder()
                .name("權責邊界品類-" + suffix)
                .build());
        Product product = products.save(Product.builder()
                .name("權責邊界商品-" + suffix)
                .category(category)
                .trackType(TrackType.A)
                .status(ProductStatus.EVALUATING)
                .cost(new BigDecimal("60.00"))
                .suggestedPrice(new BigDecimal("100.00"))
                .moq(100)
                .build());
        AppUser user = users.save(AppUser.builder()
                .email(suffix + "@integration.test")
                .passwordHash("not-a-real-password-hash")
                .displayName("整合測試使用者")
                .status(UserStatus.ACTIVE)
                .build());
        WeightVersion version = weightVersions.save(WeightVersion.builder()
                .versionNo("test-" + suffix)
                .name("權責邊界權重-" + suffix)
                .status(WeightVersionStatus.DRAFT)
                .changeNote("不得由 Agent 修改")
                .build());
        weightProfiles.save(WeightProfile.builder()
                .version(version)
                .sceneType(SceneType.REPLENISHMENT)
                .factorCode(FactorCode.TREND)
                .weight(BigDecimal.ONE)
                .build());
        entityManager.createNativeQuery("""
                        insert into grade_threshold
                            (version_id, scene_type, grade_a_min, grade_b_min)
                        values (:versionId, 'REPLENISHMENT', 80.00, 65.00)
                        """)
                .setParameter("versionId", version.getId())
                .executeUpdate();
        ProductScore score = productScores.save(ProductScore.builder()
                .product(product)
                .weightVersion(version)
                .period("2026W36")
                .sceneType(SceneType.REPLENISHMENT)
                .primary(true)
                .active(true)
                .bonusSubtotal(new BigDecimal("80.00"))
                .penaltySubtotal(new BigDecimal("5.00"))
                .finalScore(new BigDecimal("75.00"))
                .grade(Grade.B)
                .confidence(90)
                .build());
        scoreFactors.save(ScoreFactor.builder()
                .score(score)
                .factorCode(FactorCode.TREND)
                .rawValue(new BigDecimal("12.3400"))
                .normalizedValue(new BigDecimal("80.00"))
                .weight(BigDecimal.ONE)
                .penalty(false)
                .dataAvailable(true)
                .note("不得由 Agent 修改")
                .build());
        decisions.save(DecisionRecord.builder()
                .product(product)
                .score(score)
                .decision(DecisionType.WATCH)
                .followedAi(false)
                .reason("人工決策哨兵資料")
                .decidedBy(user)
                .build());
        entityManager.flush();
        return new AuthorityFixture(product);
    }

    private String scoringAndDecisionSnapshot() {
        return tableSnapshot("product_score", "id")
                + tableSnapshot("score_factor", "id")
                + tableSnapshot("decision_record", "id");
    }

    private String weightAuthoritySnapshot() {
        return tableSnapshot("weight_version", "id")
                + tableSnapshot("weight_profile", "id")
                + tableSnapshot("grade_threshold", "version_id, scene_type");
    }

    private String protectedCalibrationSnapshot(Long reportId) {
        return (String) entityManager.createNativeQuery("""
                        select jsonb_build_object(
                            'quarter', quarter,
                            'sample_size', sample_size,
                            'regression_result', regression_result,
                            'backtest_result', backtest_result,
                            'accepted_items', accepted_items,
                            'status', status,
                            'reviewed_by', reviewed_by,
                            'reviewed_at', reviewed_at,
                            'created_at', created_at
                        )::text
                        from calibration_report
                        where id = :reportId
                        """)
                .setParameter("reportId", reportId)
                .getSingleResult();
    }

    private String tableSnapshot(String table, String orderBy) {
        return (String) entityManager.createNativeQuery(
                        "select coalesce(jsonb_agg(to_jsonb(t) order by " + orderBy
                                + ")::text, '[]') from " + table + " t")
                .getSingleResult();
    }

    private record AuthorityFixture(Product product) {}
}
