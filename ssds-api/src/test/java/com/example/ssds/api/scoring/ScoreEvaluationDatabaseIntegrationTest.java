package com.example.ssds.api.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssds.ai.agent.ProductInsightAgent;
import com.example.ssds.ai.agent.RecommendationAgent;
import com.example.ssds.ai.agent.ReviewRiskAgent;
import com.example.ssds.ai.agent.SourcingScoutAgent;
import com.example.ssds.ai.agent.WeightCalibrationAgent;
import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationRequest;
import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationResult;
import com.example.ssds.api.scoring.ScoreEvaluationService.FactorInput;
import com.example.ssds.core.domain.CalendarType;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.core.domain.WeightVersionStatus;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.FestivalCalendar;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.entity.WeightProfile;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.FestivalCalendarRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.RiskAlertRepository;
import com.example.ssds.infra.repository.ScoreFactorRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.infra.repository.WeightProfileRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
class ScoreEvaluationDatabaseIntegrationTest {
    private static final Instant ATTEMPTED_AT = Instant.parse("2026-09-18T02:30:00Z");

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
    private ScoreEvaluationService service;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private ProductRepository products;
    @Autowired
    private ProductScoreRepository scores;
    @Autowired
    private ScoreFactorRepository scoreFactors;
    @Autowired
    private RiskAlertRepository riskAlerts;
    @Autowired
    private TrendKeywordRepository keywords;
    @Autowired
    private FestivalCalendarRepository festivals;
    @Autowired
    private WeightVersionRepository versions;
    @Autowired
    private WeightProfileRepository profiles;
    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void ensureCurrentWeightVersion() {
        if (versions.findByIsCurrentTrue().isPresent()) {
            return;
        }
        WeightVersion version = versions.saveAndFlush(WeightVersion.builder()
                .versionNo("it-" + UUID.randomUUID().toString().substring(0, 8))
                .name("Phase 3 integration weights")
                .status(WeightVersionStatus.DRAFT)
                .isCurrent(true)
                .build());
        for (SceneType scene : SceneType.values()) {
            profiles.saveAll(profileRows(version, scene));
            jdbc.sql("""
                    insert into grade_threshold
                        (version_id, scene_type, grade_a_min, grade_b_min)
                    values (:versionId, :scene, 80.00, 65.00)
                    """)
                    .param("versionId", version.getId())
                    .param("scene", scene.name())
                    .update();
        }
    }

    @Test
    void persistsTwoCompleteSnapshotsAndKeepsThePreviousRunAsInactiveHistory() {
        Product product = newProduct("history");
        SourceIds sources = newSources("history");

        EvaluationResult first = service.evaluate(request(product, ATTEMPTED_AT, completeFactors(sources)));
        EvaluationResult second = service.evaluate(request(
                product, ATTEMPTED_AT.plusSeconds(60), completeFactors(sources)));

        assertTrue(first.scored());
        assertTrue(second.scored());
        assertNotEquals(first.primaryScoreId(), second.primaryScoreId());
        List<ProductScore> history = scores.findByProductIdOrderByCalculatedAtDesc(product.getId());
        assertEquals(4, history.size());
        assertEquals(2, history.stream().filter(ProductScore::isActive).count());
        assertEquals(2, history.stream().filter(score -> !score.isActive()).count());
        assertEquals(1, history.stream().filter(ProductScore::isActive).filter(ProductScore::isPrimary).count());

        ProductScore primary = history.stream()
                .filter(score -> score.getId().equals(second.primaryScoreId()))
                .findFirst()
                .orElseThrow();
        assertEquals(0, primary.getBonusSubtotal().compareTo(new BigDecimal("80.00")));
        assertEquals(0, primary.getPenaltySubtotal().compareTo(new BigDecimal("3.00")));
        assertEquals(0, primary.getFinalScore().compareTo(new BigDecimal("77.00")));

        for (ProductScore active : history.stream().filter(ProductScore::isActive).toList()) {
            List<ScoreFactor> factors = scoreFactors.findByScoreId(active.getId());
            assertEquals(9, factors.size());
            assertEquals(6, factors.stream().filter(factor -> !factor.isPenalty()).count());
            assertEquals(3, factors.stream().filter(ScoreFactor::isPenalty).count());
            BigDecimal effectiveWeightSum = factors.stream()
                    .filter(factor -> !factor.isPenalty())
                    .map(ScoreFactor::getWeight)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, effectiveWeightSum.compareTo(BigDecimal.ONE));
        }
        List<ScoreFactor> primaryFactors = scoreFactors.findByScoreId(second.primaryScoreId());
        assertEquals(sources.keywordId(), factor(primaryFactors, FactorCode.TREND).getDrivingKeywordId());
        assertEquals(sources.festivalId(), factor(primaryFactors, FactorCode.FESTIVAL).getDrivingFestivalId());
    }

    @Test
    void rollsBackOldSnapshotDeactivationWhenAChildFactorCannotBeStored() {
        Product product = newProduct("rollback");
        SourceIds sources = newSources("rollback");
        EvaluationResult original = service.evaluate(request(product, ATTEMPTED_AT, completeFactors(sources)));
        Map<FactorCode, FactorInput> invalid = new EnumMap<>(completeFactors(sources));
        invalid.put(FactorCode.MARGIN, new FactorInput(
                BigDecimal.ONE,
                new BigDecimal("80"),
                null,
                true,
                false,
                "x".repeat(121)));

        assertThrows(RuntimeException.class, () -> service.evaluate(request(
                product, ATTEMPTED_AT.plusSeconds(60), invalid)));

        List<ProductScore> history = scores.findByProductIdOrderByCalculatedAtDesc(product.getId());
        assertEquals(2, history.size());
        assertEquals(2, history.stream().filter(ProductScore::isActive).count());
        assertTrue(history.stream().anyMatch(score -> score.getId().equals(original.primaryScoreId())));
        assertEquals(9, scoreFactors.findByScoreId(original.primaryScoreId()).size());
    }

    @Test
    void insufficientDataWritesNoSnapshotAndDeduplicatesTheSevenDayAlert() {
        Product product = newProduct("insufficient");
        SourceIds sources = newSources("insufficient");

        EvaluationResult first = service.evaluate(request(product, ATTEMPTED_AT, insufficientFactors(sources)));
        EvaluationResult second = service.evaluate(request(
                product, ATTEMPTED_AT.plusSeconds(86_400), insufficientFactors(sources)));

        assertFalse(first.scored());
        assertFalse(second.scored());
        assertEquals(LastScoringStatus.INSUFFICIENT_DATA, second.status());
        assertTrue(scores.findByProductIdOrderByCalculatedAtDesc(product.getId()).isEmpty());
        assertEquals(1, riskAlerts.findByProductIdOrderByDetectedAtDesc(product.getId()).size());
        Product reloaded = products.findById(product.getId()).orElseThrow();
        assertEquals(LastScoringStatus.INSUFFICIENT_DATA, reloaded.getLastScoringStatus());
        assertEquals(ATTEMPTED_AT.plusSeconds(86_400), reloaded.getLastScoringAttemptedAt());
    }

    private Product newProduct(String suffix) {
        Category category = categories.save(Category.builder()
                .name("Phase3-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .build());
        return products.saveAndFlush(Product.builder()
                .name("Phase3 product " + suffix)
                .category(category)
                .trackType(TrackType.A)
                .status(ProductStatus.EVALUATING)
                .cost(new BigDecimal("60.00"))
                .suggestedPrice(new BigDecimal("100.00"))
                .build());
    }

    private SourceIds newSources(String suffix) {
        TrendKeyword keyword = keywords.saveAndFlush(TrendKeyword.builder()
                .keyword("phase3-" + suffix + "-" + UUID.randomUUID())
                .build());
        FestivalCalendar festival = festivals.saveAndFlush(FestivalCalendar.builder()
                .festivalCode("P3_" + UUID.randomUUID().toString().substring(0, 8))
                .festivalName("Phase 3 festival")
                .calendarType(CalendarType.SOLAR)
                .festivalDate(LocalDate.of(2026, 10, 1))
                .year((short) 2026)
                .build());
        return new SourceIds(keyword.getId(), festival.getId());
    }

    private static EvaluationRequest request(
            Product product, Instant attemptedAt, Map<FactorCode, FactorInput> factors) {
        return new EvaluationRequest(
                product.getId(), SceneType.FESTIVAL, SceneType.SEASONAL, 88, attemptedAt, factors);
    }

    private static Map<FactorCode, FactorInput> completeFactors(SourceIds sources) {
        EnumMap<FactorCode, FactorInput> factors = new EnumMap<>(FactorCode.class);
        for (FactorCode code : FactorCode.values()) {
            factors.put(code, code.isPenalty()
                    ? new FactorInput(BigDecimal.ONE, null, BigDecimal.ONE, true, false, "risk")
                    : new FactorInput(BigDecimal.ONE, new BigDecimal("80"), null, true, false, "evidence"));
        }
        factors.put(FactorCode.TREND, new FactorInput(
                BigDecimal.ONE, new BigDecimal("80"), null, true, false, "trend",
                sources.keywordId(), null));
        factors.put(FactorCode.FESTIVAL, new FactorInput(
                BigDecimal.ONE, new BigDecimal("80"), null, true, false, "festival",
                null, sources.festivalId()));
        return factors;
    }

    private static Map<FactorCode, FactorInput> insufficientFactors(SourceIds sources) {
        EnumMap<FactorCode, FactorInput> factors = new EnumMap<>(FactorCode.class);
        for (FactorCode code : FactorCode.values()) {
            if (code.isPenalty()) {
                factors.put(code, new FactorInput(null, null, BigDecimal.ZERO, true, false, null));
            } else {
                boolean available = code == FactorCode.TREND || code == FactorCode.MARGIN;
                factors.put(code, new FactorInput(
                        null,
                        available ? new BigDecimal("70") : null,
                        null,
                        available,
                        false,
                        null,
                        code == FactorCode.TREND ? sources.keywordId() : null,
                        null));
            }
        }
        return factors;
    }

    private static ScoreFactor factor(List<ScoreFactor> factors, FactorCode code) {
        return factors.stream()
                .filter(factor -> factor.getFactorCode() == code)
                .findFirst()
                .orElseThrow();
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

    private record SourceIds(Long keywordId, Long festivalId) {}
}
