package com.example.ssds.api.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssds.ai.agent.TrendInterpreterAgent;
import com.example.ssds.ai.model.trend.TrendInterpreterInput;
import com.example.ssds.ai.model.trend.TrendInterpreterResult;
import com.example.ssds.core.domain.HeatGranularity;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.SourceAvailability;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_AGENT5_LIVE_TEST", matches = "true")
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate",
        "ai.external-llm-enabled=true",
        "ai.trend.schedule-enabled=false",
        "ai.sourcing.time-gap-schedule-enabled=false"
})
class Agent5LiveModelIntegrationTest {

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
    private TrendInterpreterAgent agent;

    @Test
    void liveModelReturnsOneAllowedOutput() {
        List<TrendInterpreterInput.AllowedOutput> allowed = List.of(
                new TrendInterpreterInput.AllowedOutput(HeatStage.RISING, 1, 56),
                new TrendInterpreterInput.AllowedOutput(HeatStage.PLATEAU, 1, 42),
                new TrendInterpreterInput.AllowedOutput(HeatStage.DECLINING, 1, 17));
        TrendInterpreterInput input = new TrendInterpreterInput(
                1L,
                List.of(
                        new TrendInterpreterInput.CompositePoint(
                                "2026-09-15", new BigDecimal("50.00"), null, null),
                        new TrendInterpreterInput.CompositePoint(
                                "2026-09-22", new BigDecimal("70.00"),
                                new BigDecimal("0.4000"), new BigDecimal("0.2000"))),
                List.of(new TrendInterpreterInput.SourceTrend(
                        HeatSourceCode.GOOGLE_TRENDS,
                        HeatGranularity.KEYWORD,
                        null,
                        new BigDecimal("0.4000"),
                        new BigDecimal("0.2000"),
                        SourceAvailability.AVAILABLE)),
                allowed);

        TrendInterpreterResult result = agent.interpret(input, true);

        assertFalse(result.fallbackApplied());
        assertNotNull(result.model());
        assertTrue(result.promptTokens() != null && result.promptTokens() > 0);
        assertTrue(result.completionTokens() != null && result.completionTokens() > 0);
        assertTrue(allowed.contains(new TrendInterpreterInput.AllowedOutput(
                result.output().stage(),
                result.output().stageWeeks(),
                result.output().estimatedLifespanDays())));
        System.out.printf(
                "AGENT5_LIVE_EVIDENCE model=%s promptVersion=%s promptTokens=%d completionTokens=%d requests=%d output=%s/%d/%d%n",
                result.model(),
                result.promptVersion(),
                result.promptTokens(),
                result.completionTokens(),
                result.requestCount(),
                result.output().stage(),
                result.output().stageWeeks(),
                result.output().estimatedLifespanDays());
    }
}
