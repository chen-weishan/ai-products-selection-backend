package com.example.ssds.api.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssds.ai.agent.TrendInterpreterAgent;
import com.example.ssds.ai.model.FallbackReason;
import com.example.ssds.ai.model.trend.TrendInterpreterOutput;
import com.example.ssds.ai.model.trend.TrendInterpreterResult;
import com.example.ssds.ai.prompt.trend.TrendInterpreterPromptFactory;
import com.example.ssds.api.aitask.execution.AiTaskCreatedEvent;
import com.example.ssds.api.aitask.execution.AiTaskWorker;
import com.example.ssds.api.aitask.service.AiTaskService;
import com.example.ssds.api.trend.TrendInterpretationJob;
import com.example.ssds.api.trend.TrendInterpretationService;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.AdapterType;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.HeatGranularity;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.HeatValueSource;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.core.domain.WeightVersionStatus;
import com.example.ssds.infra.dao.HeatReadingPercentileDao;
import com.example.ssds.infra.dao.TrendQueryDao;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.DecisionRecord;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.HeatReading;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.entity.TrendInterpretation;
import com.example.ssds.infra.entity.WeightProfile;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AiTaskItemRepository;
import com.example.ssds.infra.repository.AiTaskRepository;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.DecisionRecordRepository;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
import com.example.ssds.infra.repository.TrendInterpretationRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.infra.repository.WeightProfileRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import com.example.ssds.infra.service.HeatCompositeCalibrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate",
        "ai.trend.schedule-enabled=false",
        "ai.sourcing.time-gap-schedule-enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class Agent5DailyTrendDatabaseIntegrationTest {

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
    private TrendInterpreterAgent trendInterpreterAgent;

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private TrendKeywordRepository keywords;
    @Autowired
    private ProductRepository products;
    @Autowired
    private HeatSourceRepository heatSources;
    @Autowired
    private HeatReadingRepository heatReadings;
    @Autowired
    private HeatCompositeDailyRepository composites;
    @Autowired
    private SourcingCandidateRepository candidates;
    @Autowired
    private TrendInterpretationRepository interpretations;
    @Autowired
    private HeatReadingPercentileDao percentileDao;
    @Autowired
    private TrendQueryDao trendQueryDao;
    @Autowired
    private HeatCompositeCalibrationService calibrationService;
    @Autowired
    private TrendInterpretationService interpretationService;
    @Autowired
    private AiTaskService taskService;
    @Autowired
    private AiTaskWorker taskWorker;
    @Autowired
    private AiTaskRepository tasks;
    @Autowired
    private AiTaskItemRepository taskItems;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private ProductScoreRepository scores;
    @Autowired
    private WeightVersionRepository weightVersions;
    @Autowired
    private WeightProfileRepository weightProfiles;
    @Autowired
    private DecisionRecordRepository decisions;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void openApiExposesContractsUsedByFrontendGenerateApi() throws Exception {
        String document = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode openApi = objectMapper.readTree(document);

        assertAll(
                () -> assertFalse(openApi.at("/paths/~1trends~1{keywordId}/get").isMissingNode()),
                () -> assertFalse(openApi.at(
                        "/paths/~1trends~1keywords~1{keywordId}~1interpretation/post")
                        .isMissingNode()),
                () -> assertFalse(openApi.at(
                        "/paths/~1trends~1keywords~1{keywordId}~1interpretation~1latest/get")
                        .isMissingNode()),
                () -> assertFalse(openApi.at(
                        "/components/schemas/TrendKeywordDetailResponse/properties/stageSource")
                        .isMissingNode()),
                () -> assertFalse(openApi.at(
                        "/components/schemas/TrendKeywordDetailResponse/properties/lifespanSource")
                        .isMissingNode()),
                () -> assertFalse(openApi.at(
                        "/components/schemas/SourceDetail/properties/granularity")
                        .isMissingNode()),
                () -> assertFalse(openApi.at(
                        "/components/schemas/TrendInterpretationResponse/properties/fallbackApplied")
                        .isMissingNode()),
                () -> assertFalse(openApi.at(
                        "/components/schemas/TrendInterpretationResponse/properties/promptVersion")
                        .isMissingNode()),
                () -> assertFalse(openApi.at(
                        "/components/schemas/TrendInterpretationResponse/properties/model")
                        .isMissingNode()));
    }

    @Test
    void trendEndpointsReturnUsableFrontendContract() throws Exception {
        LocalDate today = LocalDate.now();
        Category category = categories.saveAndFlush(Category.builder().name("Phase5 API 品類").build());
        TrendKeyword keyword = keywords.saveAndFlush(
                TrendKeyword.builder().keyword("Phase5 API 關鍵字").enabled(true).build());
        products.saveAndFlush(Product.builder()
                .name("Phase5 API 品項")
                .category(category)
                .trackType(TrackType.A)
                .status(ProductStatus.DRAFT)
                .keywords(new LinkedHashSet<>(Set.of(keyword)))
                .build());
        HeatSource threads = saveSource(
                HeatSourceCode.THREADS,
                HeatGranularity.KEYWORD,
                "1.000",
                SourceAvailability.AVAILABLE);
        saveReading(threads, keyword, null, today.minusDays(30), "40.00");
        saveReading(threads, keyword, null, today.minusDays(7), "60.00");
        saveReading(threads, keyword, null, today, "80.00");
        composites.saveAndFlush(HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(today)
                .compositeValue(new BigDecimal("80.00"))
                .slope7d(new BigDecimal("0.3333"))
                .slope30d(new BigDecimal("1.0000"))
                .stage(HeatStage.RISING)
                .stageWeeks((short) 2)
                .estimatedLifespanDays(56)
                .stageSource(HeatValueSource.RULE)
                .lifespanSource(HeatValueSource.RULE)
                .appliedWeights("{\"THREADS\":1.0000}")
                .divergenceFlag(true)
                .volumeBelowFloor(false)
                .build());
        when(trendInterpreterAgent.interpret(any(), eq(false))).thenReturn(
                new TrendInterpreterResult(
                        new TrendInterpreterOutput(HeatStage.RISING, 1, 56),
                        false,
                        null,
                        false,
                        "agent5-api-model",
                        TrendInterpreterPromptFactory.PROMPT_VERSION,
                        20,
                        5,
                        1));
        interpretationService.interpret(keyword.getId(), false);
        entityManager.flush();

        mockMvc.perform(get("/trends/{keywordId}", keyword.getId()).param("range", "90d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(1))
                .andExpect(jsonPath("$.stage").value("RISING"))
                .andExpect(jsonPath("$.stageWeeks").value(1))
                .andExpect(jsonPath("$.estimatedLifespanDays").value(56))
                .andExpect(jsonPath("$.stageSource").value("AGENT"))
                .andExpect(jsonPath("$.lifespanSource").value("AGENT"))
                .andExpect(jsonPath("$.divergenceFlag").value(true))
                .andExpect(jsonPath("$.sourceDetails[0].granularity").value("KEYWORD"))
                .andExpect(jsonPath("$.sourceDetails[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.sourceDetails[0].appliedWeight").value(1.0));

        mockMvc.perform(get(
                        "/trends/keywords/{keywordId}/interpretation/latest",
                        keyword.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model").value("agent5-api-model"))
                .andExpect(jsonPath("$.data.promptVersion").value("trend-v4"))
                .andExpect(jsonPath("$.data.fallbackApplied").value(false));

        mockMvc.perform(post(
                        "/trends/keywords/{keywordId}/interpretation",
                        keyword.getId())
                        .param("forceRefresh", "true"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.taskType").value("TREND_INTERPRET"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void heatReadingFlowsThroughRuleBaselineAgentOverrideHistoryAndTimeGap() {
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        Category category = categories.saveAndFlush(Category.builder().name("Agent5 E2E 品類").build());
        TrendKeyword keyword = keywords.saveAndFlush(
                TrendKeyword.builder().keyword("Agent5 E2E 關鍵字").enabled(true).build());
        Product product = products.saveAndFlush(Product.builder()
                .name("Agent5 E2E B軌品項")
                .category(category)
                .trackType(TrackType.B)
                .status(ProductStatus.DRAFT)
                .sourcingStatus(SourcingStatus.PENDING)
                .keywords(new LinkedHashSet<>(Set.of(keyword)))
                .build());
        candidates.saveAndFlush(SourcingCandidate.builder()
                .product(product)
                .keyword(keyword)
                .category(category)
                .leadTimeDays(20)
                .build());
        HeatSource source = heatSources.saveAndFlush(HeatSource.builder()
                .sourceCode(HeatSourceCode.GOOGLE_TRENDS)
                .adapterType(AdapterType.REST)
                .granularity(HeatGranularity.KEYWORD)
                .compositeWeight(BigDecimal.ONE)
                .availability(SourceAvailability.AVAILABLE)
                .enabled(true)
                .build());
        for (int daysAgo = 1; daysAgo <= 7; daysAgo++) {
            saveComposite(keyword, businessDate.minusDays(daysAgo), "50.00", HeatStage.RISING);
        }
        saveComposite(keyword, businessDate.minusDays(8), "50.00", HeatStage.RISING);
        saveComposite(keyword, businessDate.minusDays(31), "50.00", HeatStage.PLATEAU);
        heatReadings.saveAndFlush(HeatReading.builder()
                .source(source)
                .keyword(keyword)
                .readingDate(businessDate)
                .rawValue(new BigDecimal("100.000"))
                .build());

        assertEquals(1, percentileDao.applyPercentiles(businessDate));
        HeatCompositeDaily baseline = calibrationService
                .computeAndPersist(keyword.getId(), businessDate)
                .orElseThrow();
        assertAll(
                () -> assertEquals(HeatStage.RISING, baseline.getStage()),
                () -> assertEquals((short) 2, baseline.getStageWeeks()),
                () -> assertEquals(HeatValueSource.RULE, baseline.getStageSource()),
                () -> assertTrue(baseline.getAppliedWeights().contains("GOOGLE_TRENDS")));

        interpretations.saveAndFlush(TrendInterpretation.builder()
                .keyword(keyword)
                .heatStage(HeatStage.PLATEAU)
                .stageWeeks((short) 1)
                .estimatedLifespanDays(42)
                .inputSnapshot("{}")
                .fallbackApplied(false)
                .model("previous-model")
                .promptVersion(TrendInterpreterPromptFactory.PROMPT_VERSION)
                .generatedAt(Instant.parse("2026-09-21T22:00:00Z"))
                .current(true)
                .build());

        when(trendInterpreterAgent.interpret(any(), eq(false))).thenReturn(
                new TrendInterpreterResult(
                        new TrendInterpreterOutput(HeatStage.DECLINING, 1, 17),
                        false,
                        null,
                        false,
                        "agent5-e2e-model",
                        TrendInterpreterPromptFactory.PROMPT_VERSION,
                        20,
                        5,
                        1));
        new TrendInterpretationJob(
                keywords, composites, interpretations, taskService, objectMapper)
                .enqueueSignificantKeywords(businessDate);
        AiTask task = tasks.findByTaskTypeOrderByStartedAtDesc(AiTaskType.TREND_INTERPRET)
                .getFirst();
        AiTaskWorker synchronousWorker = AopTestUtils.getTargetObject(taskWorker);
        synchronousWorker.run(new AiTaskCreatedEvent(task.getId(), false));
        entityManager.flush();
        entityManager.clear();

        HeatCompositeDaily overridden = composites
                .findByKeywordIdAndStatDate(keyword.getId(), businessDate)
                .orElseThrow();
        SourcingCandidate candidate = candidates.findDetailedByProductId(product.getId()).orElseThrow();
        AiTask completedTask = tasks.findById(task.getId()).orElseThrow();
        assertAll(
                () -> assertEquals(HeatStage.DECLINING, overridden.getStage()),
                () -> assertEquals((short) 1, overridden.getStageWeeks()),
                () -> assertEquals(17, overridden.getEstimatedLifespanDays()),
                () -> assertEquals(HeatValueSource.AGENT, overridden.getStageSource()),
                () -> assertEquals(HeatValueSource.AGENT, overridden.getLifespanSource()),
                () -> assertEquals(TaskStatus.SUCCEEDED, completedTask.getStatus()),
                () -> assertEquals(
                        TaskItemStatus.SUCCEEDED,
                        taskItems.findByTaskId(task.getId()).getFirst().getStatus()),
                () -> assertEquals(2, interpretations.count()),
                () -> assertTrue(interpretations.findByKeywordIdAndCurrentTrue(keyword.getId()).isPresent()),
                () -> assertEquals(keyword.getId(), candidate.getDrivingKeyword().getId()),
                () -> assertEquals(-3, candidate.getTimeGapDays()),
                () -> assertEquals(SourcingStatus.REJECTED, candidate.getProduct().getSourcingStatus()));

        calibrationService.computeAndPersist(keyword.getId(), businessDate).orElseThrow();
        entityManager.flush();
        entityManager.clear();
        HeatCompositeDaily rerun = composites
                .findByKeywordIdAndStatDate(keyword.getId(), businessDate)
                .orElseThrow();
        assertAll(
                () -> assertEquals(HeatStage.DECLINING, rerun.getStage()),
                () -> assertEquals((short) 1, rerun.getStageWeeks()),
                () -> assertEquals(17, rerun.getEstimatedLifespanDays()),
                () -> assertEquals(HeatValueSource.AGENT, rerun.getStageSource()),
                () -> assertEquals(HeatValueSource.AGENT, rerun.getLifespanSource()));

        when(trendInterpreterAgent.interpret(any(), eq(false))).thenReturn(
                new TrendInterpreterResult(
                        new TrendInterpreterOutput(HeatStage.RISING, 1, 56),
                        false,
                        null,
                        false,
                        "agent5-e2e-model",
                        TrendInterpreterPromptFactory.PROMPT_VERSION,
                        20,
                        5,
                        1));
        interpretationService.interpret(keyword.getId(), false);
        entityManager.flush();
        entityManager.clear();
        SourcingCandidate stillRejected = candidates
                .findDetailedByProductId(product.getId())
                .orElseThrow();
        assertAll(
                () -> assertEquals(3, interpretations.count()),
                () -> assertEquals(1, currentInterpretationCount(keyword.getId())),
                () -> assertEquals(
                        HeatStage.RISING,
                        interpretations.findByKeywordIdAndCurrentTrue(keyword.getId())
                                .orElseThrow()
                                .getHeatStage()),
                () -> assertEquals(SourcingStatus.REJECTED, stillRejected.getProduct().getSourcingStatus()),
                () -> assertEquals(-3, stillRejected.getTimeGapDays()));

        when(trendInterpreterAgent.interpret(any(), eq(false))).thenReturn(
                new TrendInterpreterResult(
                        new TrendInterpreterOutput(HeatStage.PLATEAU, 1, 42),
                        true,
                        FallbackReason.AI_UNAVAILABLE,
                        false,
                        "rule-fallback",
                        TrendInterpreterPromptFactory.PROMPT_VERSION,
                        null,
                        null,
                        0));
        interpretationService.interpret(keyword.getId(), false);
        entityManager.flush();
        entityManager.clear();
        HeatCompositeDaily fallbackComposite = composites
                .findByKeywordIdAndStatDate(keyword.getId(), businessDate)
                .orElseThrow();
        assertAll(
                () -> assertEquals(4, interpretations.count()),
                () -> assertEquals(1, currentInterpretationCount(keyword.getId())),
                () -> assertTrue(interpretations.findByKeywordIdAndCurrentTrue(keyword.getId())
                        .orElseThrow()
                        .isFallbackApplied()),
                () -> assertEquals(HeatValueSource.RULE, fallbackComposite.getStageSource()),
                () -> assertEquals(HeatValueSource.RULE, fallbackComposite.getLifespanSource()),
                () -> assertEquals(
                        SourcingStatus.REJECTED,
                        candidates.findDetailedByProductId(product.getId())
                                .orElseThrow()
                                .getProduct()
                                .getSourcingStatus()));
    }

    @Test
    void unavailableSourceIsExcludedAndInstagramCategoryWeightIsHalved() {
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        Category category = categories.saveAndFlush(Category.builder().name("Phase2 權重品類").build());
        TrendKeyword keyword = keywords.saveAndFlush(
                TrendKeyword.builder().keyword("Phase2 權重關鍵字").enabled(true).build());
        products.saveAndFlush(Product.builder()
                .name("Phase2 權重品項")
                .category(category)
                .trackType(TrackType.A)
                .status(ProductStatus.DRAFT)
                .keywords(new LinkedHashSet<>(Set.of(keyword)))
                .build());
        HeatSource threads = saveSource(
                HeatSourceCode.THREADS,
                HeatGranularity.KEYWORD,
                "0.400",
                SourceAvailability.AVAILABLE);
        HeatSource google = saveSource(
                HeatSourceCode.GOOGLE_TRENDS,
                HeatGranularity.KEYWORD,
                "0.400",
                SourceAvailability.UNAVAILABLE);
        HeatSource instagram = saveSource(
                HeatSourceCode.INSTAGRAM,
                HeatGranularity.CATEGORY,
                "0.200",
                SourceAvailability.AVAILABLE);
        saveReading(threads, keyword, null, businessDate, "80.00");
        saveReading(google, keyword, null, businessDate, "100.00");
        saveReading(instagram, null, category, businessDate, "20.00");

        assertAll(
                () -> assertEquals(68.0, trendQueryDao.findCompositeHeat(keyword.getId(), businessDate), 0.001),
                () -> assertEquals(
                        new BigDecimal("0.8000"),
                        trendQueryDao.findAppliedWeights(keyword.getId(), businessDate).get("THREADS")),
                () -> assertEquals(
                        new BigDecimal("0.2000"),
                        trendQueryDao.findAppliedWeights(keyword.getId(), businessDate).get("INSTAGRAM")),
                () -> assertFalse(
                        trendQueryDao.findAppliedWeights(keyword.getId(), businessDate)
                                .containsKey("GOOGLE_TRENDS")));

        HeatCompositeDaily composite = calibrationService
                .computeAndPersist(keyword.getId(), businessDate)
                .orElseThrow();
        assertAll(
                () -> assertEquals(new BigDecimal("68.00"), composite.getCompositeValue()),
                () -> assertEquals(HeatStage.PLATEAU, composite.getStage()),
                () -> assertTrue(composite.getAppliedWeights().contains("THREADS")),
                () -> assertTrue(composite.getAppliedWeights().contains("INSTAGRAM")),
                () -> assertFalse(composite.getAppliedWeights().contains("GOOGLE_TRENDS")));

        threads.setAvailability(SourceAvailability.UNAVAILABLE);
        instagram.setAvailability(SourceAvailability.UNAVAILABLE);
        heatSources.saveAllAndFlush(Set.of(threads, instagram));

        assertNull(trendQueryDao.findCompositeHeat(keyword.getId(), businessDate));
        assertTrue(trendQueryDao.findAppliedWeights(keyword.getId(), businessDate).isEmpty());
    }

    @Test
    void ruleBaselineCoversAllStagesTransitionAndMissingDayRestart() {
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        Category category = categories.saveAndFlush(Category.builder().name("Phase6 階段品類").build());
        HeatSource source = saveSource(
                HeatSourceCode.GOOGLE_TRENDS,
                HeatGranularity.KEYWORD,
                "1.000",
                SourceAvailability.AVAILABLE);

        TrendKeyword rising = saveKeywordProduct("Phase6 上升", category);
        saveComposite(rising, businessDate.minusDays(31), "50.00", HeatStage.PLATEAU);
        saveReading(source, rising, null, businessDate, "100.00");

        TrendKeyword plateau = saveKeywordProduct("Phase6 盤整", category);
        saveComposite(plateau, businessDate.minusDays(31), "50.00", HeatStage.PLATEAU);
        saveComposite(plateau, businessDate.minusDays(1), "51.00", HeatStage.RISING);
        saveReading(source, plateau, null, businessDate, "52.00");

        TrendKeyword declining = saveKeywordProduct("Phase6 衰退", category);
        saveComposite(declining, businessDate.minusDays(31), "100.00", HeatStage.RISING);
        saveComposite(declining, businessDate.minusDays(2), "55.00", HeatStage.DECLINING);
        saveReading(source, declining, null, businessDate, "50.00");

        HeatCompositeDaily risingRow = calibrationService
                .computeAndPersist(rising.getId(), businessDate).orElseThrow();
        HeatCompositeDaily plateauRow = calibrationService
                .computeAndPersist(plateau.getId(), businessDate).orElseThrow();
        HeatCompositeDaily decliningRow = calibrationService
                .computeAndPersist(declining.getId(), businessDate).orElseThrow();

        assertAll(
                () -> assertEquals(HeatStage.RISING, risingRow.getStage()),
                () -> assertEquals(HeatStage.PLATEAU, plateauRow.getStage()),
                () -> assertEquals((short) 1, plateauRow.getStageWeeks()),
                () -> assertEquals(HeatStage.DECLINING, decliningRow.getStage()),
                () -> assertEquals((short) 1, decliningRow.getStageWeeks()));
    }

    @Test
    void agent5DoesNotModifyTrackAScoreWeightsOrManualDecision() {
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        Category category = categories.saveAndFlush(Category.builder().name("Agent5 權限邊界品類").build());
        TrendKeyword keyword = keywords.saveAndFlush(
                TrendKeyword.builder().keyword("Agent5 權限邊界關鍵字").enabled(true).build());
        Product product = products.saveAndFlush(Product.builder()
                .name("Agent5 權限邊界 A 軌品項")
                .category(category)
                .trackType(TrackType.A)
                .status(ProductStatus.EVALUATING)
                .cost(new BigDecimal("60.00"))
                .suggestedPrice(new BigDecimal("100.00"))
                .keywords(new LinkedHashSet<>(Set.of(keyword)))
                .build());
        AppUser buyer = users.saveAndFlush(AppUser.builder()
                .email("agent5-boundary@example.test")
                .passwordHash("not-used-in-test")
                .displayName("Agent5 驗收")
                .build());
        WeightVersion version = weightVersions.saveAndFlush(WeightVersion.builder()
                .versionNo("agent5-boundary")
                .name("Agent5 不可修改權重")
                .status(WeightVersionStatus.DRAFT)
                .isCurrent(false)
                .changeNote("sentinel")
                .createdBy(buyer)
                .build());
        WeightProfile profile = weightProfiles.saveAndFlush(WeightProfile.builder()
                .version(version)
                .sceneType(SceneType.REPLENISHMENT)
                .factorCode(FactorCode.TREND)
                .weight(BigDecimal.ONE)
                .build());
        ProductScore score = scores.saveAndFlush(ProductScore.builder()
                .product(product)
                .weightVersion(version)
                .period("2026W39")
                .sceneType(SceneType.REPLENISHMENT)
                .primary(true)
                .active(true)
                .bonusSubtotal(new BigDecimal("80.00"))
                .penaltySubtotal(new BigDecimal("5.00"))
                .finalScore(new BigDecimal("75.00"))
                .grade(Grade.B)
                .confidence(88)
                .build());
        DecisionRecord decision = decisions.saveAndFlush(DecisionRecord.builder()
                .product(product)
                .score(score)
                .decision(DecisionType.WATCH)
                .followedAi(false)
                .reason("人工維持觀察")
                .decidedBy(buyer)
                .build());
        composites.saveAndFlush(HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(businessDate)
                .compositeValue(new BigDecimal("80.00"))
                .slope7d(new BigDecimal("0.2000"))
                .slope30d(new BigDecimal("0.3000"))
                .stage(HeatStage.RISING)
                .stageWeeks((short) 1)
                .estimatedLifespanDays(56)
                .stageSource(HeatValueSource.RULE)
                .lifespanSource(HeatValueSource.RULE)
                .appliedWeights("{}")
                .divergenceFlag(false)
                .volumeBelowFloor(false)
                .build());
        long scoreCount = scores.count();
        long versionCount = weightVersions.count();
        long profileCount = weightProfiles.count();
        long decisionCount = decisions.count();
        when(trendInterpreterAgent.interpret(any(), eq(false))).thenReturn(
                new TrendInterpreterResult(
                        new TrendInterpreterOutput(HeatStage.RISING, 1, 56),
                        false,
                        null,
                        false,
                        "agent5-boundary-model",
                        TrendInterpreterPromptFactory.PROMPT_VERSION,
                        20,
                        5,
                        1));

        interpretationService.interpret(keyword.getId(), false);
        entityManager.flush();
        entityManager.clear();

        ProductScore unchangedScore = scores.findById(score.getId()).orElseThrow();
        WeightVersion unchangedVersion = weightVersions.findById(version.getId()).orElseThrow();
        WeightProfile unchangedProfile = weightProfiles.findById(profile.getId()).orElseThrow();
        DecisionRecord unchangedDecision = decisions.findById(decision.getId()).orElseThrow();
        assertAll(
                () -> assertEquals(scoreCount, scores.count()),
                () -> assertEquals(new BigDecimal("75.00"), unchangedScore.getFinalScore()),
                () -> assertEquals(Grade.B, unchangedScore.getGrade()),
                () -> assertEquals(88, unchangedScore.getConfidence()),
                () -> assertTrue(unchangedScore.isActive()),
                () -> assertEquals(versionCount, weightVersions.count()),
                () -> assertEquals("sentinel", unchangedVersion.getChangeNote()),
                () -> assertFalse(unchangedVersion.isCurrent()),
                () -> assertEquals(profileCount, weightProfiles.count()),
                () -> assertEquals(BigDecimal.ONE.setScale(3), unchangedProfile.getWeight()),
                () -> assertEquals(decisionCount, decisions.count()),
                () -> assertEquals(DecisionType.WATCH, unchangedDecision.getDecision()),
                () -> assertFalse(unchangedDecision.isFollowedAi()),
                () -> assertEquals("人工維持觀察", unchangedDecision.getReason()));
    }

    @Test
    void findsOnlyEnabledKeywordsMissingTheRequestedDailyComposite() {
        LocalDate businessDate = LocalDate.of(2031, 1, 2);
        TrendKeyword missing = keywords.saveAndFlush(
                TrendKeyword.builder().keyword("補跑缺漏關鍵字").enabled(true).build());
        TrendKeyword completed = keywords.saveAndFlush(
                TrendKeyword.builder().keyword("補跑完成關鍵字").enabled(true).build());
        TrendKeyword disabled = keywords.saveAndFlush(
                TrendKeyword.builder().keyword("補跑停用關鍵字").enabled(false).build());
        saveComposite(completed, businessDate, "50.00", HeatStage.PLATEAU);
        composites.flush();

        List<Long> result = composites.findEnabledKeywordIdsMissingStatDate(businessDate);

        assertAll(
                () -> assertTrue(result.contains(missing.getId())),
                () -> assertFalse(result.contains(completed.getId())),
                () -> assertFalse(result.contains(disabled.getId())));
    }

    private HeatSource saveSource(
            HeatSourceCode sourceCode,
            HeatGranularity granularity,
            String weight,
            SourceAvailability availability) {
        return heatSources.saveAndFlush(HeatSource.builder()
                .sourceCode(sourceCode)
                .adapterType(AdapterType.REST)
                .granularity(granularity)
                .compositeWeight(new BigDecimal(weight))
                .availability(availability)
                .enabled(true)
                .build());
    }

    private long currentInterpretationCount(Long keywordId) {
        return interpretations.findAll().stream()
                .filter(value -> value.getKeyword().getId().equals(keywordId) && value.isCurrent())
                .count();
    }

    private TrendKeyword saveKeywordProduct(String name, Category category) {
        TrendKeyword keyword = keywords.saveAndFlush(
                TrendKeyword.builder().keyword(name).enabled(true).build());
        products.saveAndFlush(Product.builder()
                .name(name + "品項")
                .category(category)
                .trackType(TrackType.A)
                .status(ProductStatus.DRAFT)
                .keywords(new LinkedHashSet<>(Set.of(keyword)))
                .build());
        return keyword;
    }

    private void saveReading(
            HeatSource source,
            TrendKeyword keyword,
            Category category,
            LocalDate date,
            String percentile) {
        heatReadings.saveAndFlush(HeatReading.builder()
                .source(source)
                .keyword(keyword)
                .category(category)
                .readingDate(date)
                .rawValue(new BigDecimal(percentile))
                .percentileWithinSource(new BigDecimal(percentile))
                .build());
    }

    private void saveComposite(
            TrendKeyword keyword, LocalDate date, String value, HeatStage stage) {
        composites.save(HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(date)
                .compositeValue(new BigDecimal(value))
                .stage(stage)
                .stageWeeks((short) 1)
                .estimatedLifespanDays(stage == HeatStage.RISING ? 56 : 42)
                .stageSource(HeatValueSource.RULE)
                .lifespanSource(HeatValueSource.RULE)
                .appliedWeights("{}")
                .divergenceFlag(false)
                .volumeBelowFloor(false)
                .build());
    }
}
