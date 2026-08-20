package com.example.ssds.infra.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在乾淨的 PostgreSQL 容器上，從 V1 一路建到最新，驗證 migration 本身是好的。
 *
 * <p>存在的理由：共用資料庫永遠是「增量套用」，一支新 migration 在上面跑得過，
 * 不代表整套 V1→Vn 從空庫建得起來。舊有的 contextLoads 測試又直接連共用庫，
 * 等於用正式資料當測試環境。本測試把兩件事都拆掉——不碰共用庫，也不需要 .env。
 *
 * <p>執行前提：本機有執行中的 Docker Desktop。
 * 指令：{@code .\gradlew.bat :ssds-infra:test}
 */
@Testcontainers
class MigrationVerificationTest {

    /**
     * 刻意不加 static：每個測試方法各起一個全新容器，確保「從空庫開始」。
     * 映像鎖定 17.6 而非浮動的 17-alpine，與共用資料庫的 PostgreSQL 17.6 一致；
     * 浮動標籤會讓「本機驗過」與「共用庫實際跑的版本」悄悄分岔。
     */
    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Test
    @DisplayName("prod 版面：只載入 db/migration，從空庫建到最新且無待套用項目")
    void prodLayoutBuildsFromScratch() {
        Flyway flyway = flyway("classpath:db/migration");

        MigrateResult result = flyway.migrate();

        assertTrue(result.success, "Flyway 套用失敗");
        assertTrue(result.migrationsExecuted > 0, "沒有任何 migration 被執行，locations 可能設錯");
        // validate() 在 checksum 或版本不符時直接拋例外
        flyway.validate();
        assertEquals(0, flyway.info().pending().length, "仍有未套用的 migration");
        assertAllApplied(flyway);
    }

    @Test
    @DisplayName("dev 版面：db/migration 加上 db/dev 假資料，從空庫建到最新")
    void devLayoutBuildsFromScratch() {
        // 對齊 application-dev.properties 的設定
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration", "classpath:db/dev")
                .outOfOrder(true)
                .load();

        MigrateResult result = flyway.migrate();

        assertTrue(result.success, "Flyway 套用失敗");
        flyway.validate();
        assertEquals(0, flyway.info().pending().length, "仍有未套用的 migration");
        assertAllApplied(flyway);
    }

    /**
     * V12 之後的約定：public 底下每一張表都必須啟用 RLS。
     *
     * <p>V12 只處理了它套用當下已存在的表，之後新增的表不會自動被涵蓋。
     * 這個測試會擋下「新 migration 建了表卻沒開 RLS」的 PR ——
     * 補救方式是在該支 migration 內加上
     * {@code ALTER TABLE <表名> ENABLE ROW LEVEL SECURITY;}
     *
     * <p>flyway_schema_history 例外：migration 執行期間 Flyway 另一條連線正持有
     * 該表的鎖，在 migration 內對它下 ALTER TABLE 會永久互等。它改由第一層的
     * REVOKE 收乾淨權限來保護（REVOKE 的鎖層級較弱，實測不受影響），
     * 由 supabaseLikeRolesAreLockedDown 驗證。
     */
    @Test
    @DisplayName("安全底線：套用完畢後 public 底下沒有任何未啟用 RLS 的表")
    void everyPublicTableHasRowLevelSecurityEnabled() {
        flyway("classpath:db/migration").migrate();

        List<String> unprotected = queryStrings("""
                SELECT c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relkind IN ('r', 'p')
                  AND c.relname <> 'flyway_schema_history'
                  AND NOT c.relrowsecurity
                ORDER BY c.relname
                """);

        assertTrue(unprotected.isEmpty(),
                "以下資料表未啟用 RLS，請在對應的 migration 內補上 "
                        + "ALTER TABLE ... ENABLE ROW LEVEL SECURITY：" + unprotected);
    }

    /**
     * 模擬 Supabase 的角色設定，驗證 V12 第一層真的跑得完、也真的收得乾淨。
     *
     * <p>乾淨的 PostgreSQL 沒有 anon / authenticated，V12 第一層會整段跳過，
     * 等於平常的測試碰不到那段程式碼 —— 而那正是會在共用資料庫上執行的部分。
     * 本測試先把兩個角色和 Supabase 式的 default privileges 建起來，讓
     * V1～V11 建的表自動被授權給 anon，重現正式環境的狀態後再套用 V12。
     */
    @Test
    @DisplayName("Supabase 版面：有 anon/authenticated 時，套用後權限確實被收乾淨")
    void supabaseLikeRolesAreLockedDown() {
        execute("CREATE ROLE anon NOLOGIN");
        execute("CREATE ROLE authenticated NOLOGIN");
        execute("GRANT USAGE ON SCHEMA public TO anon, authenticated");
        // Supabase 靠 default privileges 讓新建的表自動對外開放，這裡照做
        execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO anon, authenticated");

        flyway("classpath:db/migration").migrate();

        // 查「有效權限」而非 information_schema 的直接授權紀錄：
        // has_*_privilege 會把經由偽角色 PUBLIC 繼承來的權限一併算進去，
        // 只查 role_table_grants 的話，PUBLIC 那條路會整個看不到。
        // 含 flyway_schema_history：它不在 RLS 的保護範圍內，這裡是唯一的防線。
        List<String> reachableTables = queryStrings("""
                SELECT c.relname || ' / ' || r.rolname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN (SELECT unnest(ARRAY['anon', 'authenticated']) AS rolname) r
                WHERE n.nspname = 'public'
                  AND c.relkind IN ('r', 'p')
                  AND (has_table_privilege(r.rolname, c.oid, 'SELECT')
                    OR has_table_privilege(r.rolname, c.oid, 'INSERT')
                    OR has_table_privilege(r.rolname, c.oid, 'UPDATE')
                    OR has_table_privilege(r.rolname, c.oid, 'DELETE'))
                ORDER BY 1
                """);
        assertTrue(reachableTables.isEmpty(), "以下資料表仍可被存取：" + reachableTables);

        List<String> reachableSequences = queryStrings("""
                SELECT c.relname || ' / ' || r.rolname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN (SELECT unnest(ARRAY['anon', 'authenticated']) AS rolname) r
                WHERE n.nspname = 'public'
                  AND c.relkind = 'S'
                  AND (has_sequence_privilege(r.rolname, c.oid, 'SELECT')
                    OR has_sequence_privilege(r.rolname, c.oid, 'USAGE')
                    OR has_sequence_privilege(r.rolname, c.oid, 'UPDATE'))
                ORDER BY 1
                """);
        assertTrue(reachableSequences.isEmpty(), "以下 sequence 仍可被存取：" + reachableSequences);

        // default privileges 也要收掉，否則下一支 migration 建的物件又會自動對外開放。
        // 函式特別重要：它的預設授權對象是 PUBLIC，Supabase 的 RPC 端點走這條路。
        execute("CREATE TABLE public.probe_table_after_v12 (id INT)");
        execute("CREATE FUNCTION public.probe_fn_after_v12() RETURNS INT LANGUAGE SQL AS 'SELECT 1'");

        List<String> probeReachable = queryStrings("""
                SELECT 'probe_table_after_v12 / ' || rolname
                FROM (SELECT unnest(ARRAY['anon', 'authenticated']) AS rolname) r
                WHERE has_table_privilege(rolname, 'public.probe_table_after_v12', 'SELECT')
                UNION ALL
                SELECT 'probe_fn_after_v12() / ' || rolname
                FROM (SELECT unnest(ARRAY['anon', 'authenticated']) AS rolname) r
                WHERE has_function_privilege(rolname, 'public.probe_fn_after_v12()', 'EXECUTE')
                ORDER BY 1
                """);
        assertTrue(probeReachable.isEmpty(),
                "V12 之後新建的物件仍可被存取：" + probeReachable
                        + "，代表 ALTER DEFAULT PRIVILEGES 沒有涵蓋到");
    }

    // ---------------------------------------------------------------

    private Flyway flyway(String... locations) {
        return Flyway.configure()
                // 用 url/user/password 而非 DataSource 物件：PostgreSQL driver 是
                // runtimeOnly，編譯期取不到 PGSimpleDataSource
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(locations)
                .load();
    }

    private void assertAllApplied(Flyway flyway) {
        for (MigrationInfo info : flyway.info().applied()) {
            assertFalse(info.getState().isFailed(),
                    "migration 套用失敗：" + info.getVersion() + " " + info.getDescription());
        }
    }

    private void execute(String sql) {
        try (Connection conn = DriverManager.getConnection(
                     postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("執行失敗：" + sql, e);
        }
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
