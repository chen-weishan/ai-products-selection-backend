package com.example.ssds.api.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.ssds.ai.agent.SourcingScoutAgent;
import com.example.ssds.ai.model.SourcingScoutOutput;
import com.example.ssds.ai.model.SourcingScoutResult;
import com.example.ssds.api.sourcing.SourcingScoutService;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.entity.TrendInterpretation;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.AiTaskRepository;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
import com.example.ssds.infra.repository.TrendInterpretationRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
    private HeatCompositeDailyRepository heatComposites;
    @Autowired
    private TrendInterpretationRepository trends;
    @Autowired
    private SourcingCandidateRepository candidates;
    @Autowired
    private SourcingScoutService sourcingScoutService;

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
    @DisplayName("Agent 6: latest heat_composite_daily is authoritative over history and model output")
    void agent6UsesLatestCompositeWithoutConflictingTrendOverride() {
        Category category = categories.save(Category.builder().name("測試品類").build());
        TrendKeyword keyword = keywords.save(TrendKeyword.builder().keyword("DB整合測試關鍵字").build());
        Product product = products.save(Product.builder()
                .name("DB整合測試商品")
                .category(category)
                .trackType(TrackType.B)
                .status(ProductStatus.DRAFT)
                .sourcingStatus(SourcingStatus.PENDING)
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
                .trendInterpretation(conflictingTrend)
                .build());
        entityManager.clear();

        when(sourcingScoutAgent.scout(any(), eq(false))).thenReturn(new SourcingScoutResult(
                new SourcingScoutOutput(
                        "探索報告內容", List.of("機會訊號"), List.of("風險訊號"), HeatStage.DECLINING),
                false, "scout-test-model", "scout-v5", 10, 5, 1));

        sourcingScoutService.scout(product.getId(), false);
        entityManager.flush();
        entityManager.clear();

        SourcingCandidate reloaded = candidates.findDetailedByProductId(product.getId()).orElseThrow();
        assertAll(
                () -> assertEquals(HeatStage.PLATEAU, reloaded.getHeatStage()),
                () -> assertEquals((short) 4, reloaded.getStageWeeks()),
                () -> assertEquals(35, reloaded.getEstimatedLifespanDays()),
                () -> assertEquals(15, reloaded.getTimeGapDays()),
                () -> assertEquals(SourcingStatus.SOURCING, reloaded.getProduct().getSourcingStatus()),
                () -> assertNull(reloaded.getTrendInterpretation()));
    }
}
