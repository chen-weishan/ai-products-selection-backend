package com.example.ssds.api.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssds.core.domain.AdapterType;
import com.example.ssds.core.domain.HeatGranularity;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.dao.TrendQueryDao;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.HeatReading;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * heat_source.enabled 只控制「要不要採集」，不參與合成熱度：
 * <ul>
 *   <li>當日（Instagram 為當週）已有讀值 → 停用後仍計入</li>
 *   <li>當日沒有讀值 → 自然不計入，權重在其餘來源間重新正規化</li>
 *   <li>要讓舊讀值退出評分，只能走 availability = UNAVAILABLE</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate",
        "ai.trend.schedule-enabled=false",
        "ai.sourcing.time-gap-schedule-enabled=false"
})
@Transactional
class HeatSourceEnabledCompositeIntegrationTest {

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
    private TrendQueryDao trendQueryDao;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 24); // 週四

    @Test
    void disabledKeywordSourceWithTodaysReadingStillCounts() {
        Category category = categories.saveAndFlush(Category.builder().name("enabled 規則品類 A").build());
        TrendKeyword keyword = saveKeywordProduct("enabled 規則關鍵字 A", category);
        HeatSource threads = saveSource(HeatSourceCode.THREADS, HeatGranularity.KEYWORD,
                "0.400", SourceAvailability.AVAILABLE, false);
        HeatSource google = saveSource(HeatSourceCode.GOOGLE_TRENDS, HeatGranularity.KEYWORD,
                "0.400", SourceAvailability.AVAILABLE, true);
        saveReading(threads, keyword, null, DAY, "80.00");
        saveReading(google, keyword, null, DAY, "100.00");

        assertAll(
                () -> assertEquals(90.0, trendQueryDao.findCompositeHeat(keyword.getId(), DAY), 0.001),
                () -> assertEquals(new BigDecimal("0.5000"),
                        trendQueryDao.findAppliedWeights(keyword.getId(), DAY).get("THREADS")),
                () -> assertEquals(new BigDecimal("0.5000"),
                        trendQueryDao.findAppliedWeights(keyword.getId(), DAY).get("GOOGLE_TRENDS")));
    }

    @Test
    void disabledKeywordSourceWithoutTodaysReadingIsExcludedAndWeightsRenormalize() {
        Category category = categories.saveAndFlush(Category.builder().name("enabled 規則品類 B").build());
        TrendKeyword keyword = saveKeywordProduct("enabled 規則關鍵字 B", category);
        HeatSource threads = saveSource(HeatSourceCode.THREADS, HeatGranularity.KEYWORD,
                "0.400", SourceAvailability.AVAILABLE, false);
        HeatSource google = saveSource(HeatSourceCode.GOOGLE_TRENDS, HeatGranularity.KEYWORD,
                "0.400", SourceAvailability.AVAILABLE, true);
        // Threads 只有昨天的讀值（停用後沒再抓），今天只有 Google。
        saveReading(threads, keyword, null, DAY.minusDays(1), "80.00");
        saveReading(google, keyword, null, DAY, "100.00");

        assertAll(
                () -> assertEquals(100.0, trendQueryDao.findCompositeHeat(keyword.getId(), DAY), 0.001),
                () -> assertEquals(new BigDecimal("1.0000"),
                        trendQueryDao.findAppliedWeights(keyword.getId(), DAY).get("GOOGLE_TRENDS")),
                () -> assertFalse(trendQueryDao.findAppliedWeights(keyword.getId(), DAY)
                        .containsKey("THREADS")));
    }

    @Test
    void disabledInstagramStillCountsWhenItsWeekAlreadyHasAReading() {
        Category category = categories.saveAndFlush(Category.builder().name("enabled 規則品類 C").build());
        TrendKeyword keyword = saveKeywordProduct("enabled 規則關鍵字 C", category);
        HeatSource google = saveSource(HeatSourceCode.GOOGLE_TRENDS, HeatGranularity.KEYWORD,
                "0.400", SourceAvailability.AVAILABLE, true);
        HeatSource instagram = saveSource(HeatSourceCode.INSTAGRAM, HeatGranularity.CATEGORY,
                "0.200", SourceAvailability.AVAILABLE, false);
        LocalDate monday = DAY.minusDays(3);
        saveReading(instagram, null, category, monday, "20.00");
        saveReading(google, keyword, null, DAY, "100.00");

        // 與 Agent5DailyTrendDatabaseIntegrationTest 的週頻案例同一組數字：(100*0.4 + 20*0.1) / 0.5 = 84
        assertAll(
                () -> assertEquals(84.0, trendQueryDao.findCompositeHeat(keyword.getId(), DAY), 0.001),
                () -> assertEquals(new BigDecimal("0.2000"),
                        trendQueryDao.findAppliedWeights(keyword.getId(), DAY).get("INSTAGRAM")));

        // 下一週沒有新讀值 → 自然退出
        LocalDate nextMonday = monday.plusWeeks(1);
        saveReading(google, keyword, null, nextMonday, "70.00");
        assertAll(
                () -> assertEquals(70.0, trendQueryDao.findCompositeHeat(keyword.getId(), nextMonday), 0.001),
                () -> assertFalse(trendQueryDao.findAppliedWeights(keyword.getId(), nextMonday)
                        .containsKey("INSTAGRAM")));
    }

    @Test
    void unavailableStillExcludesEvenWhenDisabledSourceHasTodaysReading() {
        Category category = categories.saveAndFlush(Category.builder().name("enabled 規則品類 D").build());
        TrendKeyword keyword = saveKeywordProduct("enabled 規則關鍵字 D", category);
        HeatSource threads = saveSource(HeatSourceCode.THREADS, HeatGranularity.KEYWORD,
                "0.400", SourceAvailability.UNAVAILABLE, false);
        HeatSource google = saveSource(HeatSourceCode.GOOGLE_TRENDS, HeatGranularity.KEYWORD,
                "0.400", SourceAvailability.AVAILABLE, true);
        saveReading(threads, keyword, null, DAY, "80.00");
        saveReading(google, keyword, null, DAY, "100.00");

        assertAll(
                () -> assertEquals(100.0, trendQueryDao.findCompositeHeat(keyword.getId(), DAY), 0.001),
                () -> assertFalse(trendQueryDao.findAppliedWeights(keyword.getId(), DAY)
                        .containsKey("THREADS")));

        // 全部不可用 → null（資料不足不懲罰）
        google.setAvailability(SourceAvailability.UNAVAILABLE);
        heatSources.saveAndFlush(google);
        assertNull(trendQueryDao.findCompositeHeat(keyword.getId(), DAY));
        assertTrue(trendQueryDao.findAppliedWeights(keyword.getId(), DAY).isEmpty());
    }

    @Test
    void breakdownListsDisabledSourcesWithEnabledFlagAndKeepsTheirAvailability() {
        Category category = categories.saveAndFlush(Category.builder().name("enabled 規則品類 E").build());
        TrendKeyword keyword = saveKeywordProduct("enabled 規則關鍵字 E", category);
        HeatSource threads = saveSource(HeatSourceCode.THREADS, HeatGranularity.KEYWORD,
                "0.400", SourceAvailability.AVAILABLE, false);
        HeatSource google = saveSource(HeatSourceCode.GOOGLE_TRENDS, HeatGranularity.KEYWORD,
                "0.400", SourceAvailability.AVAILABLE, true);
        saveReading(threads, keyword, null, DAY, "80.00");
        saveReading(google, keyword, null, DAY, "100.00");

        var rows = trendQueryDao.findSourceBreakdown(keyword.getId());
        var threadsRow = rows.stream().filter(r -> "THREADS".equals(r.sourceCode())).findFirst().orElseThrow();
        var googleRow = rows.stream().filter(r -> "GOOGLE_TRENDS".equals(r.sourceCode())).findFirst().orElseThrow();

        assertAll(
                () -> assertFalse(threadsRow.enabled()),
                () -> assertEquals("AVAILABLE", threadsRow.availability()),
                () -> assertEquals(0, new BigDecimal("80.00").compareTo(threadsRow.percentileWithinSource())),
                () -> assertTrue(googleRow.enabled()));

        // 停用且完全沒有讀值：仍列出，但不能被標成 DEGRADED（不是資料異常）
        saveSource(HeatSourceCode.INSTAGRAM, HeatGranularity.KEYWORD,
                "0.100", SourceAvailability.AVAILABLE, false);
        var noReading = trendQueryDao.findSourceBreakdown(keyword.getId()).stream()
                .filter(r -> r.sourceCode().equals("INSTAGRAM")).findFirst().orElseThrow();
        assertAll(
                () -> assertFalse(noReading.enabled()),
                () -> assertEquals("AVAILABLE", noReading.availability()),
                () -> assertNull(noReading.percentileWithinSource()));
    }

    private HeatSource saveSource(
            HeatSourceCode code, HeatGranularity granularity, String weight,
            SourceAvailability availability, boolean enabled) {
        return heatSources.saveAndFlush(HeatSource.builder()
                .sourceCode(code)
                .adapterType(AdapterType.REST)
                .granularity(granularity)
                .compositeWeight(new BigDecimal(weight))
                .availability(availability)
                .enabled(enabled)
                .build());
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
            HeatSource source, TrendKeyword keyword, Category category,
            LocalDate date, String percentile) {
        heatReadings.saveAndFlush(HeatReading.builder()
                .source(source)
                .keyword(keyword)
                .category(category)
                .readingDate(date)
                .rawValue(new BigDecimal(percentile))
                .percentileWithinSource(new BigDecimal(percentile))
                .build());
    }
}