package com.example.ssds.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import com.example.ssds.ai.agent.ProductInsightAgent;
import com.example.ssds.ai.agent.RecommendationAgent;
import com.example.ssds.ai.agent.ReviewRiskAgent;
import com.example.ssds.ai.agent.SourcingScoutAgent;
import com.example.ssds.ai.agent.WeightCalibrationAgent;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.report.dto.ReportGenerateRequest;
import com.example.ssds.api.report.dto.ReportJobResponse;
import com.example.ssds.api.report.model.ReportDataset;
import com.example.ssds.api.report.service.ReportAsyncCoordinator;
import com.example.ssds.api.report.service.ReportCommandService;
import com.example.ssds.api.report.service.ReportDataDao;
import com.example.ssds.api.report.service.ReportFilterOptionsService;
import com.example.ssds.api.report.service.ReportJobWorker;
import com.example.ssds.api.report.service.ReportQueryService;
import com.example.ssds.core.domain.ReportFormat;
import com.example.ssds.core.domain.ReportType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.ReportJob;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.ReportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration,classpath:db/dev",
        "spring.jpa.hibernate.ddl-auto=validate",
        "ssds.report.resume-on-startup=false",
        "ai.full-analysis.schedule-enabled=false",
        "ai.trend.schedule-enabled=false",
        "ai.calibration.schedule-enabled=false"
})
class ReportDatabaseIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
    private static final Path REPORT_ROOT = Path.of("build", "tmp", "report-db-it")
            .toAbsolutePath().normalize();

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
        registry.add("ssds.report.storage-path", () -> REPORT_ROOT.toString());
    }

    @MockitoBean private SourcingScoutAgent sourcingScoutAgent;
    @MockitoBean private ReviewRiskAgent reviewRiskAgent;
    @MockitoBean private ProductInsightAgent productInsightAgent;
    @MockitoBean private RecommendationAgent recommendationAgent;
    @MockitoBean private WeightCalibrationAgent weightCalibrationAgent;
    @MockitoBean private ReportAsyncCoordinator async;

    @Autowired private ReportDataDao reports;
    @Autowired private ReportFilterOptionsService filterOptions;
    @Autowired private JdbcClient jdbc;
    @Autowired private ReportCommandService commands;
    @Autowired private ReportQueryService queries;
    @Autowired private ReportJobWorker worker;
    @Autowired private ReportJobRepository jobs;
    @Autowired private AppUserRepository users;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private ObjectMapper mapper;

    private String currentPeriod;

    @BeforeEach
    void initializePeriod() {
        currentPeriod = jdbc.sql("select max(period) from product_score where is_active = true")
                .query(String.class)
                .single();
    }

    @Test
    void weeklyPickRunsAgainstRealSchemaWithFourBoardsAndReadableRecommendation() {
        ReportDataset result = reports.load(
                ReportType.WEEKLY_PICK, params("period", currentPeriod));

        assertEquals(List.of(
                        "話題爆款榜 Top 10",
                        "節慶檔期榜 Top 10",
                        "常態補貨榜 Top 10",
                        "季節導向榜 Top 10"),
                result.sections().subList(0, 4).stream().map(section -> section.title()).toList());
        assertEquals(5, result.sections().size());
        assertTrue(result.sections().subList(0, 4).stream()
                .allMatch(section -> section.rows().size() <= 10));

        List<String> summaries = result.sections().subList(0, 4).stream()
                .flatMap(section -> section.rows().stream())
                .map(row -> row.get("ai_summary").toString())
                .filter(summary -> !"—".equals(summary))
                .toList();
        assertFalse(summaries.isEmpty());
        assertTrue(summaries.stream().allMatch(summary -> summary.startsWith("建議：")));
        assertTrue(summaries.stream().noneMatch(summary -> summary.startsWith("{")));
    }

    @Test
    void scoreDetailRunsAgainstRealSchema() {
        ReportDataset result = reports.load(
                ReportType.SCORE_DETAIL, params("period", currentPeriod));

        assertEquals("品項評分明細", result.title());
        assertFalse(result.sections().getFirst().rows().isEmpty());
        assertTrue(result.sections().getFirst().columns().stream()
                .anyMatch(column -> "normalized_value".equals(column.key())));
    }

    @Test
    void accuracyRunsAgainstRealSchema() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Map<String, Object> params = new HashMap<>();
        params.put("from", today.minusYears(1).toString());
        params.put("to", today.toString());

        ReportDataset result = reports.load(ReportType.ACCURACY, params);

        assertEquals("決策準確度", result.title());
        assertEquals(2, result.sections().size());
        assertFalse(result.sections().getFirst().rows().isEmpty());
        assertTrue(result.sections().getFirst().rows().getFirst().containsKey("score_result_correlation"));
    }

    @Test
    void sourcingQueueRunsAgainstRealSchema() {
        ReportDataset result = reports.load(ReportType.SOURCING_QUEUE, Map.of());

        assertEquals("尋源優先序", result.title());
        assertFalse(result.sections().getFirst().rows().isEmpty());
        assertTrue(result.sections().getFirst().rows().getFirst().containsKey("time_gap_days"));
    }

    @Test
    void calibrationRunsAgainstRealSchema() {
        ReportDataset result = reports.load(ReportType.CALIBRATION, Map.of());

        assertEquals("權重校準紀錄", result.title());
        assertFalse(result.sections().getFirst().rows().isEmpty());
        assertTrue(result.sections().getFirst().rows().getFirst().containsKey("accepted_items"));
    }

    @Test
    void filterOptionsRunAgainstRealSchema() {
        var result = filterOptions.get();

        assertFalse(result.categories().isEmpty());
        assertFalse(result.decisionMakers().isEmpty());
        assertFalse(result.calibrationQuarters().isEmpty());
    }

    @Test
    void workerGeneratesARealXlsxAndPersistsLifecycleState() throws Exception {
        AppUser owner = users.findByEmail("buyer@ssds.dev").orElseThrow();
        ReportJob job = jobs.saveAndFlush(ReportJob.builder()
                .reportType(ReportType.SCORE_DETAIL)
                .format(ReportFormat.XLSX)
                .paramsJson(mapper.writeValueAsString(Map.of("period", currentPeriod)))
                .status(TaskStatus.PENDING)
                .requestedBy(owner)
                .build());

        worker.generate(job.getId());

        ReportJob completed = jobs.findById(job.getId()).orElseThrow();
        assertEquals(TaskStatus.SUCCEEDED, completed.getStatus());
        assertTrue(completed.getRowCount() > 0);
        assertTrue(Files.isRegularFile(Path.of(completed.getFilePath())));
    }

    @Test
    void generationAndDownloadArePersistedInAuditAndDownloadIsOwnerOnly() throws Exception {
        AppUser owner = users.findByEmail("buyer@ssds.dev").orElseThrow();
        AppUser anotherUser = users.findByEmail("lead@ssds.dev").orElseThrow();
        ReportJobResponse generated = commands.generate(
                new ReportGenerateRequest(
                        ReportType.WEEKLY_PICK,
                        ReportFormat.PDF,
                        Map.of("period", currentPeriod)),
                owner.getEmail(),
                "127.0.0.10");
        auditLogs.flush();

        assertEquals(TaskStatus.PENDING, generated.status());
        verify(async).submit(generated.id());
        assertEquals(1, auditCount(generated.id(), "REPORT_GENERATE"));

        Path directory = Files.createDirectories(REPORT_ROOT.resolve("2026"));
        Path file = Files.writeString(directory.resolve("download-owner-test.pdf"), "report");
        ReportJob downloadable = jobs.saveAndFlush(ReportJob.builder()
                .reportType(ReportType.WEEKLY_PICK)
                .format(ReportFormat.PDF)
                .paramsJson("{}")
                .status(TaskStatus.SUCCEEDED)
                .filePath(file.toString())
                .rowCount(1)
                .requestedBy(owner)
                .build());

        assertEquals(file, queries.download(
                downloadable.getId(), owner.getEmail(), "127.0.0.11").path());
        auditLogs.flush();
        assertEquals(1, auditCount(downloadable.getId(), "REPORT_DOWNLOAD"));
        assertThrows(BusinessException.class, () -> queries.download(
                downloadable.getId(), anotherUser.getEmail(), "127.0.0.12"));
    }

    private int auditCount(long jobId, String action) {
        return jdbc.sql("""
                        select count(*) from audit_log
                        where entity_type = 'REPORT_JOB' and entity_id = :jobId and action = :action
                        """)
                .param("jobId", jobId)
                .param("action", action)
                .query(Integer.class)
                .single();
    }

    private Map<String, Object> params(String key, Object value) {
        Map<String, Object> params = new HashMap<>();
        params.put(key, value);
        return params;
    }
}
