package com.example.ssds.infra.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demisak 維護的 B 軌尋源 migration 回歸測試。
 *
 * <p>本測試獨立於原有 {@link MigrationVerificationTest}，專門驗證 V22 與
 * dev-only V899/V908 對既有 V903 假資料的相容轉換。
 */
@Testcontainers
class DemisakMigrationVerificationTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Test
    @DisplayName("Demisak：V903 假資料經 V899/V908 後符合 V22 尋源模型")
    void legacySourcingSeedIsAlignedToV22() {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration", "classpath:db/dev")
                .outOfOrder(true)
                .load();

        assertTrue(flyway.migrate().success, "Flyway 套用失敗");
        flyway.validate();
        assertEquals(0, flyway.info().pending().length, "仍有未套用的 migration");

        assertEquals(List.of("retry_pool_request_count"), queryStrings("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'ai_task'
                  AND column_name = 'retry_pool_request_count'
                  AND is_nullable = 'NO'
                """));

        // V899 只暫時補回 V903 需要的舊欄位；V908 後的最終 schema
        // 必須與正式 V22 相同。
        assertEquals(List.of("driving_keyword_id", "time_gap_days"), queryStrings("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'sourcing_candidate'
                  AND column_name IN (
                      'driving_keyword_id', 'time_gap_days',
                      'heat_stage', 'stage_weeks', 'estimated_lifespan_days')
                ORDER BY column_name
                """));

        assertEquals(List.of("lifespan_source", "stage_source"), queryStrings("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'heat_composite_daily'
                  AND column_name IN ('stage_source', 'lifespan_source')
                  AND is_nullable = 'NO'
                ORDER BY column_name
                """));

        // V903 的四筆候選都必須由 driving keyword 最新的每日合成列產生落差。
        assertEquals(4, queryStrings("""
                SELECT sc.id::text
                FROM sourcing_candidate sc
                JOIN heat_composite_daily h
                  ON h.keyword_id = sc.driving_keyword_id
                 AND h.stat_date = (
                     SELECT max(h2.stat_date)
                     FROM heat_composite_daily h2
                     WHERE h2.keyword_id = sc.driving_keyword_id)
                WHERE sc.time_gap_days = h.estimated_lifespan_days - sc.lead_time_days
                ORDER BY sc.id
                """).size(), "V903 的尋源候選未全部轉為 V22 的權威資料流");
    }

    @Test
    @DisplayName("Demisak：既有 V903 假資料可 out-of-order 套用 V22")
    void v22MigratesExistingV903SeedOutOfOrder() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("21")
                .load()
                .migrate();

        // 模擬共用開發庫：正式 migration 目前只到 V21，但 V899～V907
        // 的 dev-only 假資料已經存在。
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/dev")
                .target("907")
                .outOfOrder(true)
                .validateOnMigrate(false)
                .load()
                .migrate();

        Flyway v22 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("22")
                .outOfOrder(true)
                .validateOnMigrate(false)
                .load();

        assertTrue(v22.migrate().success, "V22 out-of-order 套用失敗");
        assertEquals(List.of("22"), queryStrings("""
                SELECT version
                FROM flyway_schema_history
                WHERE version = '22' AND success
                """));
        assertEquals(List.of("driving_keyword_id", "time_gap_days"), queryStrings("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'sourcing_candidate'
                  AND column_name IN (
                      'driving_keyword_id', 'time_gap_days',
                      'heat_stage', 'stage_weeks', 'estimated_lifespan_days')
                ORDER BY column_name
                """));
    }

    private List<String> queryStrings(String sql) {
        List<String> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(
                     postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("查詢失敗：" + sql, e);
        }
        return rows;
    }
}
