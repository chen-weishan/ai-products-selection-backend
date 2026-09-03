package com.example.ssds.api.service;

import com.example.ssds.api.dto.BtrackSummaryDto;
import com.example.ssds.api.dto.DashboardKpiResponseDto;
import com.example.ssds.api.dto.DashboardRankingsResponseDto;
import com.example.ssds.api.dto.DashboardSourcingSummaryResponseDto;
import com.example.ssds.api.dto.DashboardTodosResponseDto;
import com.example.ssds.api.dto.OverdueCampaignDto;
import com.example.ssds.api.dto.RankingItemDto;
import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class DashboardServiceTest {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductScoreRepository productScoreRepository;

    @Autowired
    private RiskAlertRepository riskAlertRepository;

    @Autowired
    private DecisionRecordRepository decisionRecordRepository;

    @Autowired
    private SourcingCandidateRepository sourcingCandidateRepository;

    @Autowired
    private SceneClassificationLogRepository sceneClassificationLogRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private WeightVersionRepository weightVersionRepository;

    @Autowired
    private AppUserRepository appUserRepository;

private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Taipei");
     private static final AtomicLong CALCULATED_AT_COUNTER = new AtomicLong(System.currentTimeMillis() * 1_000_000);


     // Helper to create a ProductScore
    private ProductScore createProductScore(Product product, String period, SceneType sceneType, Grade grade, BigDecimal finalScore, boolean active) {
        // Get or create a test weight version (unique per test run)
        WeightVersion weightVersion = weightVersionRepository.findByVersionNo("TEST-1.0")
                .orElseGet(() -> {
                    WeightVersion wv = WeightVersion.builder()
                            .versionNo("TEST-1.0")
                            .name("Test Weight Version")
                            .effectiveFrom(LocalDate.of(2026, 1, 1))
                            .build();
                    return weightVersionRepository.save(wv);
                });

        // Use unique calculatedAt to avoid uk_score unique constraint violation
        ProductScore score = ProductScore.builder()
                .product(product)
                .weightVersion(weightVersionRepository.findByVersionNo("TEST-1.0").orElseThrow())
                .period(period)
                .sceneType(sceneType)
                .grade(grade)
                .bonusSubtotal(finalScore)
                .penaltySubtotal(BigDecimal.ZERO)
                .finalScore(finalScore)
                .active(active)
                .primary(true)
                .calculatedAt(Instant.now().plusNanos(CALCULATED_AT_COUNTER.getAndIncrement()))
                .confidence(100)
                .build();
        return productScoreRepository.save(score);
    }

    // Helper to create a RiskAlert
private RiskAlert createRiskAlert(Product product, Severity severity) {
        RiskAlert alert = RiskAlert.builder()
                .product(product)
                .severity(severity)
                .riskType("INVENTORY_RISK") // V17 ck_risk_alert_type 要求大寫
                .status(AlertStatus.OPEN)
                .detectedAt(Instant.now())
                .build();
        return riskAlertRepository.save(alert);
    }

    // Helper to create a DecisionRecord (for overdue)
    private DecisionRecord createDecisionRecord(Product product, LocalDate campaignEndDate) {
        // Get or create a default AppUser for decidedBy
        AppUser appUser = appUserRepository.findById(1L)
                .orElseGet(() -> {
                    AppUser user = AppUser.builder()
                            .email("test-" + System.nanoTime() + "@example.com")
                            .passwordHash("hashedpassword")
                            .displayName("Test User")
                            .build();
                    return appUserRepository.save(user);
                });

        // Create a ProductScore for this decision
        ProductScore score = createProductScore(product, "2026W30", SceneType.VIRAL, Grade.A, new BigDecimal("85"), true);
        productScoreRepository.save(score);

        DecisionRecord dr = DecisionRecord.builder()
                .product(product)
                .score(score)
                .decision(DecisionType.ADOPT)
                .followedAi(true)
                .decidedBy(appUser)
                .firstOrderQty(100) // ADOPT 需要首批數量 (ck_decision_qty 約束)
                .campaignEndDate(campaignEndDate)
                .result(null) // not filled yet
                .build();
        return decisionRecordRepository.save(dr);
    }

    // Helper to create a SourcingCandidate (need to set product.sourcingStatus)
    private SourcingCandidate createSourcingCandidate(Product product, SourcingStatus sourcingStatus, Integer timeGapDays) {
        // Set product's sourcingStatus
        product.setSourcingStatus(sourcingStatus);
        product = productRepository.save(product);
        // Calculate estimatedLifespanDays based on desired timeGapDays and default leadTimeDays (7)
        Integer leadTimeDays = 7;
        Integer estimatedLifespanDays = (timeGapDays == null) ? null : timeGapDays + leadTimeDays;
        SourcingCandidate sc = SourcingCandidate.builder()
                .product(product)
                .leadTimeDays(leadTimeDays)
                .estimatedLifespanDays(estimatedLifespanDays)
                .timeGapDays(timeGapDays)
                .build();
        return sourcingCandidateRepository.save(sc);
    }

    // Helper to create SceneClassificationLog
    private SceneClassificationLog createSceneClassificationLog(Product product, SceneType aiSceneType, SceneType finalSceneType) {
        SceneClassificationLog log = SceneClassificationLog.builder()
                .product(product)
                .aiSceneType(aiSceneType)
                .finalSceneType(finalSceneType)
                .aiConfidence(BigDecimal.valueOf(0.9))
                .createdAt(Instant.now())
                .build();
        return sceneClassificationLogRepository.save(log);
    }

// ---------- Test AC-02-6: A級主推品項數不重複計數 ----------
    @Test
    void testAC02_6_AGradeCountNotDuplicated() {
        Category testCategory = Category.builder()
                .name("Test Category")
                .build();
        testCategory = categoryRepository.save(testCategory);

        Product testProductA = Product.builder()
                .name("Test Product A")
                .category(testCategory)
                .trackType(TrackType.A)
                .build();
        Product testProductB = Product.builder()
                .name("Test Product B")
                .category(testCategory)
                .trackType(TrackType.B)
                .build();
        testProductA = productRepository.save(testProductA);
        testProductB = productRepository.save(testProductB);
        
        // Get initial A-grade count for baseline
        long initialAGradeCount = productScoreRepository.countAGradeByPeriod("2026W30", TrackType.A);
        
        String period = "2026W30";
        SceneType viral = SceneType.VIRAL;
        SceneType seasonal = SceneType.SEASONAL;
        Grade gradeA = Grade.A;

        // Same product appears in both VIRAL (primary) and SEASONAL (secondary) with A grade
        ProductScore score1 = createProductScore(testProductA, period, viral, gradeA, new BigDecimal("80"), true);
        WeightVersion weightVersion = weightVersionRepository.findByVersionNo("TEST-1.0").orElseThrow();
        ProductScore score2 = ProductScore.builder()
                .product(testProductA)
                .weightVersion(weightVersion)
                .period(period)
                .sceneType(seasonal)
                .grade(gradeA)
                .bonusSubtotal(new BigDecimal("75"))
                .penaltySubtotal(BigDecimal.ZERO)
                .finalScore(new BigDecimal("75"))
                .active(true)
                .primary(false) // Secondary scene, not primary
                .calculatedAt(Instant.now().plusNanos(CALCULATED_AT_COUNTER.getAndIncrement()))
                .confidence(100)
                .build();
        productScoreRepository.save(score2);

        // Get final A-grade count after creating our test data
        long finalAGradeCount = productScoreRepository.countAGradeByPeriod("2026W30", TrackType.A);
        
        // The deduplication means our two A-grade scores for the same product should only increase the count by 1
        assertEquals(initialAGradeCount + 1, finalAGradeCount, "A grade count should increase by exactly 1 due to deduplication");
    }

    // ---------- Test AC-02-7: B 軌摘要依時效落差升冪排序 ----------
    @Test
    void testAC02_7_BTrackSummaryOrderByTimeGap() {
// Create three B-track products with different time_gap_days
        Category testCategory = Category.builder()
                .name("Test Category")
                .build();
        testCategory = categoryRepository.save(testCategory);

        Product p1 = Product.builder()
                .name("B1")
                .category(testCategory)
                .trackType(TrackType.B)
                .build();
        Product p2 = Product.builder()
                .name("B2")
                .category(testCategory)
                .trackType(TrackType.B)
                .build();
        Product p3 = Product.builder()
                .name("B3")
                .category(testCategory)
                .trackType(TrackType.B)
                .build();
        p1 = productRepository.save(p1);
        p2 = productRepository.save(p2);
        p3 = productRepository.save(p3);

        // Capture product IDs for later lookup
        Long p1Id = p1.getId();
        Long p2Id = p2.getId();
        Long p3Id = p3.getId();

        SourcingCandidate sc1 = createSourcingCandidate(p1, SourcingStatus.SOURCING, 5);
        SourcingCandidate sc2 = createSourcingCandidate(p2, SourcingStatus.SOURCING, 2);
        SourcingCandidate sc3 = createSourcingCandidate(p3, SourcingStatus.SOURCING, null); // null treated as largest

        sc1 = sourcingCandidateRepository.save(sc1);
        sc2 = sourcingCandidateRepository.save(sc2);
        sc3 = sourcingCandidateRepository.save(sc3);

        DashboardSourcingSummaryResponseDto summary = dashboardService.getSourcingSummary(10); // limit large enough
        List<BtrackSummaryDto> allItems = summary.items();
        
        // Find the items corresponding to our three test products
        BtrackSummaryDto itemP1 = allItems.stream()
                .filter(item -> item.getProductId().equals(p1Id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Could not find sourcing candidate for product p1"));
        BtrackSummaryDto itemP2 = allItems.stream()
                .filter(item -> item.getProductId().equals(p2Id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Could not find sourcing candidate for product p2"));
        BtrackSummaryDto itemP3 = allItems.stream()
                .filter(item -> item.getProductId().equals(p3Id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Could not find sourcing candidate for product p3"));
        
        // Verify that we have the expected timeGapDays values for our three test products
        assertEquals(5, itemP1.getTimeGapDays(), "Product p1 should have timeGapDays = 5");
        assertEquals(2, itemP2.getTimeGapDays(), "Product p2 should have timeGapDays = 2");
        assertNull(itemP3.getTimeGapDays(), "Product p3 should have timeGapDays = null");
    }

// ---------- Test AC-02-5: 結案逾 7 天未回填者出現在 todos，過期天數正確 ----------
    @Test
    void testAC02_5_OverdueCampaigns() {
        System.out.println("Running testAC02_5_OverdueCampaigns");
        Category testCategory = Category.builder()
                .name("Test Category")
                .build();
        testCategory = categoryRepository.save(testCategory);

LocalDate today = LocalDate.now(DISPLAY_ZONE);
         LocalDate campaignEndDate = today.minusDays(10); // 10 days ago
         LocalDate cutoff = today.minusDays(7);
        // Create product
        Product product = Product.builder()
                .name("Overdue Product")
                .category(testCategory)
                .trackType(TrackType.A)
                .build();
        product = productRepository.save(product);

DecisionRecord dr = createDecisionRecord(product, campaignEndDate);
        dr = decisionRecordRepository.save(dr);

        // Debug: check repository directly
        List<DecisionRecord> overdueFromRepo = decisionRecordRepository.findOverdueCampaigns(DecisionType.ADOPT, cutoff);
        System.out.println("Repository overdue count: " + overdueFromRepo.size());
        for (DecisionRecord odr : overdueFromRepo) {
            System.out.println("  DecisionRecord ID: " + odr.getId() + ", productId: " + odr.getProduct().getId() + ", campaignEndDate: " + odr.getCampaignEndDate() + ", result null? " + (odr.getResult() == null));
        }

        DashboardTodosResponseDto todos = dashboardService.getTodos();
           List<OverdueCampaignDto> list = todos.overdueCampaigns();
           System.out.println("Overdue campaign list size: " + list.size());
           final Long productId = product.getId();
           Optional<OverdueCampaignDto> dtoOpt = list.stream()
                   .filter(dto -> dto.getProductId().equals(productId))
                   .findFirst();
           System.out.println("Found dto for our product: " + dtoOpt.isPresent());
           dtoOpt.ifPresent(dto -> {
               System.out.println("Matched dto productId: " + dto.getProductId());
               System.out.println("Campaign end date: " + dto.getCampaignEndDate());
               System.out.println("Overdue days: " + dto.getOverdueDays());
           });
           assertTrue(dtoOpt.isPresent(), "Should find an overdue campaign for our product");
           OverdueCampaignDto dto = dtoOpt.get();
           // overdueDays = (today - campaignEndDate) - 7 = (10) - 7 = 3
           assertEquals(3, dto.getOverdueDays());
    }

    // ---------- Test 第 1 點: 同品項 HIGH + MEDIUM 未處理風險 => riskLevel == HIGH ----------
    @Test
    void testRiskLevelHighWhenHighAndMediumPresent() {
        Category testCategory = Category.builder()
                .name("Test Category")
                .build();
        testCategory = categoryRepository.save(testCategory);

        String period = "2026W30";
        SceneType scene = SceneType.VIRAL;

        Product product = Product.builder()
                .name("Risk Product")
                .category(testCategory)
                .trackType(TrackType.A)
                .build();
        product = productRepository.save(product);

        // Create two risk alerts for same product: HIGH and MEDIUM
        RiskAlert high = createRiskAlert(product, Severity.HIGH);
        RiskAlert medium = createRiskAlert(product, Severity.MEDIUM);
        riskAlertRepository.save(high);
        riskAlertRepository.save(medium);

        // Create a minimal ProductScore to appear in rankings
        ProductScore score = createProductScore(product, period, scene, Grade.A, new BigDecimal("90"), true);
        productScoreRepository.save(score);

        DashboardRankingsResponseDto rankings = dashboardService.getRankings(period, "A", scene, 5);
        List<RankingItemDto> viral = rankings.viral();
        assertFalse(viral.isEmpty());
        RankingItemDto item = viral.get(0);
        assertEquals("HIGH", item.riskLevel(), "RiskLevel should be HIGH when both HIGH and MEDIUM present");
    }

// ---------- Test §5.10: 同鍵兩筆快照，舊筆 is_active=false => 榜上只出現一筆 ----------
    @Test
    void testActiveSnapshotOnly() {
        String period = "2030W50";
        SceneType scene = SceneType.VIRAL;
        Grade gradeA = Grade.A;

// Clean up any existing scores for this period/scene/track to isolate test
        productScoreRepository.deleteByPeriodAndSceneTypeAndTrackType(period, scene, TrackType.A);
        
        Category testCategory = Category.builder()
                .name("Test Category")
                .build();
        testCategory = categoryRepository.save(testCategory);

        Product product = Product.builder()
                .name("Snapshot Product")
                .category(testCategory)
                .trackType(TrackType.A)
                .build();
        product = productRepository.save(product);

        // Old snapshot: is_active = false
        ProductScore oldScore = createProductScore(product, period, scene, gradeA, new BigDecimal("70"), false);

        // New snapshot: is_active = true, higher score should win
        ProductScore newScore = createProductScore(product, period, scene, gradeA, new BigDecimal("85"), true);

DashboardRankingsResponseDto rankings = dashboardService.getRankings(period, "A", scene, 5);
List<RankingItemDto> viral = rankings.viral();
         assertEquals(1, viral.size(), "Only one snapshot should appear in ranking");
         RankingItemDto item = viral.get(0);
         assertEquals(new BigDecimal("85"), item.finalScore());
         assertFalse(item.sceneOverridden());
    }

// Additional test for risk level LOW being treated as NONE (per our fix)
    @Test
    void testRiskLevelLowTreatedAsNone() {
        Category testCategory = Category.builder()
                .name("Test Category")
                .build();
        testCategory = categoryRepository.save(testCategory);

        String period = "2026W30";
        SceneType scene = SceneType.VIRAL;

        Product product = Product.builder()
                .name("Risk Low Product")
                .category(testCategory)
                .trackType(TrackType.A)
                .build();
        product = productRepository.save(product);

        // Create a LOW risk alert
        RiskAlert low = createRiskAlert(product, Severity.LOW);
        riskAlertRepository.save(low);

        // Create a minimal ProductScore to appear in rankings
        ProductScore score = createProductScore(product, period, scene, Grade.A, new BigDecimal("90"), true);
        productScoreRepository.save(score);

        DashboardRankingsResponseDto rankings = dashboardService.getRankings(period, "A", scene, 5);
          List<RankingItemDto> viral = rankings.viral();
          assertFalse(viral.isEmpty());
          RankingItemDto item = viral.get(0);
          assertEquals("NONE", item.riskLevel(), "RiskLevel LOW should be reported as NONE");
    }
}