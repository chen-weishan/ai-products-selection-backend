package com.example.ssds.api.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssds.ai.model.FallbackReason;
import com.example.ssds.api.trend.TrendInterpretationService;
import com.example.ssds.api.trend.dto.TrendInterpretationResponse;
import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.HeatValueSource;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.TrendInterpretationRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
        "ai.external-llm-enabled=false",
        "ai.trend.schedule-enabled=false",
        "ai.sourcing.time-gap-schedule-enabled=false"
})
@Transactional
class Agent5FallbackDatabaseIntegrationTest {

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
    private TrendKeywordRepository keywords;
    @Autowired
    private HeatCompositeDailyRepository composites;
    @Autowired
    private TrendInterpretationRepository interpretations;
    @Autowired
    private TrendInterpretationService service;
    @Autowired
    private EntityManager entityManager;

    @Test
    void disabledExternalModelUsesRealRuleFallbackAndPersistsHistory() {
        LocalDate businessDate = LocalDate.of(2026, 9, 22);
        TrendKeyword keyword = keywords.saveAndFlush(
                TrendKeyword.builder().keyword("Phase6 純 fallback").enabled(true).build());
        composites.saveAndFlush(HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(businessDate)
                .compositeValue(new BigDecimal("75.00"))
                .slope7d(new BigDecimal("0.2000"))
                .slope30d(new BigDecimal("0.5000"))
                .stage(HeatStage.RISING)
                .stageWeeks((short) 1)
                .estimatedLifespanDays(56)
                .stageSource(HeatValueSource.RULE)
                .lifespanSource(HeatValueSource.RULE)
                .appliedWeights("{}")
                .divergenceFlag(false)
                .volumeBelowFloor(false)
                .build());

        TrendInterpretationResponse response = service.interpret(keyword.getId(), true);
        entityManager.flush();
        entityManager.clear();

        HeatCompositeDaily composite = composites
                .findByKeywordIdAndStatDate(keyword.getId(), businessDate)
                .orElseThrow();
        var history = interpretations.findByKeywordIdAndCurrentTrue(keyword.getId()).orElseThrow();
        assertAll(
                () -> assertTrue(response.fallbackApplied()),
                () -> assertEquals(FallbackReason.AI_UNAVAILABLE.name(), response.fallbackReason()),
                () -> assertEquals("policy-blocked", response.model()),
                () -> assertEquals(HeatStage.RISING, composite.getStage()),
                () -> assertEquals(HeatValueSource.RULE, composite.getStageSource()),
                () -> assertEquals(HeatValueSource.RULE, composite.getLifespanSource()),
                () -> assertTrue(history.isFallbackApplied()),
                () -> assertEquals("rule-fallback", history.getModel()),
                () -> assertTrue(history.isCurrent()),
                () -> assertEquals(1, interpretations.count()));
    }
}
