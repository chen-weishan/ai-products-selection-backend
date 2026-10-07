package com.example.ssds.api.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.RiskAlert;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.RiskAlertRepository;

/**
 * FR-10 併發：用真的 PostgreSQL 驗證兩種同時發生的情況。
 *
 * <p>刻意不加 {@code @Transactional}：測試資料必須真的提交，另一條執行緒才看得到；
 * 資料庫容器只給這個類別用，所以不另外清資料。
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate",
        "ai.external-llm-enabled=false",
        "ai.trend.schedule-enabled=false",
        "ai.sourcing.time-gap-schedule-enabled=false",
        "ssds.risk.startup-run.enabled=false"
})
class RiskAlertConcurrencyDatabaseTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

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
    private RiskAlertCommandService service;

    @Autowired
    private RiskAlertRepository alerts;

    @Autowired
    private AuditLogRepository audits;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private ProductRepository products;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
        SecurityContextHolder.clearContext();
    }

    private record Fixture(Long userId, Long alertId) {}

    private Fixture fixture() {
        Category category = categories.save(Category.builder().name("併發測試品類").build());
        Product product = products.save(Product.builder()
                .name("併發測試品項")
                .category(category)
                .trackType(TrackType.A)
                .build());
        AppUser user = users.save(AppUser.builder()
                .email("concurrency-" + System.nanoTime() + "@example.com")
                .passwordHash("hashedpassword")
                .displayName("併發測試員")
                .build());
        RiskAlert alert = alerts.save(RiskAlert.builder()
                .product(product)
                .riskType("REVIEW_RISK")
                .severity(Severity.HIGH)
                .status(AlertStatus.OPEN)
                .triggerValue("原始觸發值")
                .detectedAt(Instant.now().minusSeconds(3600))
                .build());
        return new Fixture(user.getId(), alert.getId());
    }

    private static <T> T asUser(Long userId, Callable<T> action) throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null));
        try {
            return action.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** 情境 1：兩個人同時處理同一筆，只能有一個成功，另一個收到 409，稽核只有一筆。 */
    @Test
    void twoPeopleHandlingSameAlertAtOnce_onlyOneWins() throws Exception {
        Fixture f = fixture();
        CountDownLatch start = new CountDownLatch(1);

        Future<Object> acknowledge = pool.submit(() -> asUser(f.userId(), () -> {
            start.await();
            return service.acknowledge(f.alertId());
        }));
        Future<Object> ignore = pool.submit(() -> asUser(f.userId(), () -> {
            start.await();
            return service.ignore(f.alertId(), "同時忽略測試");
        }));
        start.countDown();

        List<Throwable> failures = new ArrayList<>();
        int successes = 0;
        for (Future<Object> future : List.of(acknowledge, ignore)) {
            try {
                future.get(20, TimeUnit.SECONDS);
                successes++;
            } catch (ExecutionException exception) {
                failures.add(exception.getCause());
            }
        }

        assertEquals(1, successes, "兩個同時的處理只能成功一個");
        assertEquals(1, failures.size());
        BusinessException conflict = assertInstanceOf(BusinessException.class, failures.get(0));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, conflict.getErrorCode());

        RiskAlert after = alerts.findById(f.alertId()).orElseThrow();
        assertNotNull(after.getHandledAt());
        long auditCount = audits.findAll().stream()
                .filter(log -> "RiskAlert".equals(log.getEntityType()) && f.alertId().equals(log.getEntityId()))
                .count();
        assertEquals(1, auditCount, "稽核紀錄不能重複");
        AuditLog audit = audits.findAll().stream()
                .filter(log -> "RiskAlert".equals(log.getEntityType()) && f.alertId().equals(log.getEntityId()))
                .findFirst().orElseThrow();
        assertEquals(after.getStatus() == AlertStatus.ACKNOWLEDGED ? "ACKNOWLEDGE" : "IGNORE", audit.getAction(),
                "稽核動作要和最後的狀態一致");
    }

    /**
     * 情境 2：偵測程式讀到 OPEN 的示警、準備更新觸發值時，剛好有人確認並提交。
     * 偵測寫回後，狀態不能被蓋回 OPEN。流程與 {@code RiskAlertWriter} 的刷新一致：
     * 先讀出既有示警，改觸發值與偵測時間，再存回。
     */
    @Test
    void detectorRefreshDoesNotUndoHumanHandling() throws Exception {
        Fixture f = fixture();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch detectorLoaded = new CountDownLatch(1);
        CountDownLatch humanDone = new CountDownLatch(1);

        Future<?> detector = pool.submit(() -> tx.executeWithoutResult(status -> {
            RiskAlert existing = alerts.findById(f.alertId()).orElseThrow();
            existing.setTriggerValue("偵測更新後的觸發值");
            existing.setDetectedAt(Instant.now());
            detectorLoaded.countDown();
            try {
                humanDone.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            alerts.save(existing);
        }));

        detectorLoaded.await(20, TimeUnit.SECONDS);
        asUser(f.userId(), () -> service.acknowledge(f.alertId()));
        humanDone.countDown();
        detector.get(20, TimeUnit.SECONDS);

        RiskAlert after = alerts.findById(f.alertId()).orElseThrow();
        assertEquals(AlertStatus.ACKNOWLEDGED, after.getStatus(), "人工確認的狀態不能被偵測寫回蓋掉");
        assertNotNull(after.getHandledAt());
        assertNotNull(after.getHandledBy(), "處理人員不能被蓋掉");
        assertEquals("偵測更新後的觸發值", after.getTriggerValue(), "偵測要更新的欄位仍要寫入");
    }
}
