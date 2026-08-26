-- ===================================================================
-- V16 建立受限應用角色 ssds_app，讓組員不必再用 postgres 連共用資料庫
--
-- 【事故背景】
-- 2026-08-21 共用資料庫（Supabase 專案 aozddonvsfdrtwpuxnqi）全組 Flyway
-- 啟動失敗。原因是組員以 postgres 連線，為了繞過 V12 的約束衝突，手動
-- DROP 掉約束並刪除 flyway_schema_history 中 V12 那一列。
-- postgres 是 public 底下所有表的 owner，能 DROP 任何表、能改任何約束，
-- 也能改 Flyway 自己的帳本 —— 那次事故不是誰特別粗心，是每個人手上都握著
-- 一把能砸穿整個資料庫的鑰匙。
--
-- 【本檔要達成的狀態】
-- 組員日常連線改用 ssds_app：讀得到、寫得進去，但
--   - 不是任何表的 owner ⇒ 不能 DROP／ALTER／加減約束
--   - 沒有 TRUNCATE ⇒ 不能一鍵清空
--   - 對 flyway_schema_history 只有 SELECT ⇒ 改不了 Flyway 帳本
--   - 沒有 schema public 的 CREATE ⇒ 不能自己建表
-- schema 的變更集中回 migration 這條路，這正是 Flyway 的前提。
--
-- 【必須配合的三件事，缺一則本檔等於白做】
--   1. Vincent 在 Supabase Dashboard 輪換 postgres 密碼
--      （Settings → Database → Reset database password）。
--      舊密碼已經在五個人手上，不換的話大家照樣能用 postgres 連。
--   2. 為 ssds_app 設定密碼。本檔刻意「不」設密碼 —— migration 檔案進版控，
--      任何寫在裡面的密碼等同公開。角色建出來時無密碼即無法登入，
--      要由具權限者另外執行（此語句不要進版控）：
--          ALTER ROLE ssds_app WITH PASSWORD '<自行產生的強密碼>';
--   3. 組員的 .env 改成 ssds_app，並設 spring.flyway.enabled=false。
--      ssds_app 寫不了 flyway_schema_history，開著 Flyway 只會在啟動時失敗。
--      共用庫的 migration 由單一負責人以 postgres 套用。
--
-- 【為什麼要逐表建 policy】
-- V12 對 public 每張表啟用了 RLS 且刻意不建任何 policy（預設全拒）。
-- 那時能成立，是因為唯一的連線身分 postgres 同時是 owner 又有 BYPASSRLS，
-- RLS 對它不生效。ssds_app 兩者皆非，沒有 policy 就會被全數擋下 ——
-- 症狀是「查詢成功但永遠 0 列、INSERT 報 row-level security policy 違反」。
-- postgres 不是 superuser，無法授予 BYPASSRLS（該屬性只有 superuser 能給，
-- 2026-08-21 實測確認），所以只能逐表補 permissive policy。
-- 這些 policy 一律 USING (true) WITH CHECK (true)：本專案的權限邊界在
-- 表層 GRANT，不在資料列。RLS 在這裡是被迫繞過的機制，不是設計的一部分。
--
-- 【！！ 日後每一支新增資料表的 migration 都必須做兩件事 ！！】
--   ALTER TABLE <新表> ENABLE ROW LEVEL SECURITY;          -- 既有規則
--   CREATE POLICY p_ssds_app_rw ON <新表>                  -- 本檔新增的規則
--       FOR ALL TO ssds_app USING (true) WITH CHECK (true);
-- 少了第二行，新表對 ssds_app 就是一張永遠空的表，而且不會有任何錯誤訊息
-- 提醒你。MigrationVerificationTest 有對應的守門測試會擋下來。
-- （表層 GRANT 由本檔的 ALTER DEFAULT PRIVILEGES 自動涵蓋，policy 不行 ——
--   PostgreSQL 沒有「預設 policy」這種東西。）
--
-- 【相容性】
-- 本檔在乾淨的 PostgreSQL（Testcontainers）上一樣跑得完：CREATE ROLE 與
-- policy 都是標準語法，不依賴任何 Supabase 專有角色。
-- ===================================================================


-- 與 V12 同樣的理由：本檔要對大量既有表下 GRANT 與 CREATE POLICY，
-- 有連線壓著鎖時會無限等待。設了上限才會在 30 秒內失敗並指出卡在哪張表。
SET LOCAL lock_timeout = '30s';


-- ===================================================================
-- 第一部分：建立角色
-- ===================================================================
-- LOGIN 但不給密碼（理由見檔頭）。明確寫出所有 NO* 屬性而非依賴預設值，
-- 是為了讓「這個角色到底能做什麼」在檔案裡一眼看得完，不必去查預設。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ssds_app') THEN
        CREATE ROLE ssds_app
            LOGIN
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOREPLICATION
            NOBYPASSRLS
            INHERIT;
    END IF;
END $$;

COMMENT ON ROLE ssds_app IS
    '應用程式與開發者的日常連線角色（V16 建立）。非 owner、無 DDL、無 TRUNCATE，'
    '對 flyway_schema_history 只有 SELECT。schema 變更一律走 migration 由 postgres 套用';


-- ===================================================================
-- 第二部分：schema 層權限
-- ===================================================================
-- USAGE 是存取 schema 內物件的前提，沒有它連 SELECT 都做不到。
GRANT USAGE ON SCHEMA public TO ssds_app;

-- 收回偽角色 PUBLIC 的 CREATE。
--
-- 【現況：這一行目前是空操作，留著是防回歸】
-- 2026-08-21 實測共用資料庫（PostgreSQL 17.6），schema public 的 ACL 為
--     {pg_database_owner=UC/pg_database_owner,
--      =U/pg_database_owner,              ← 這是 PUBLIC，只有 U（USAGE）
--      postgres=U/pg_database_owner,
--      service_role=U/pg_database_owner}
-- PUBLIC 只有 USAGE 沒有 CREATE（has_schema_privilege('public','public','CREATE')
-- 回 false 已確認），PostgreSQL 15 之後的原生預設亦然。
-- 所以 ssds_app 本來就拿不到 CREATE，本行執行時只會發一則
-- 「no privileges could be revoked」的 WARNING，不會中止 migration。
--
-- 那為什麼還要留：若日後有人為了方便下了 GRANT CREATE ON SCHEMA public
-- TO PUBLIC（Supabase 舊版專案的預設就是如此），這一行是唯一擋得住的地方。
-- 空操作的成本是一則 WARNING，漏掉的成本是 ssds_app 可以在 public 建表。
--
-- 權限確認：postgres 只被直接授與 USAGE，但它是本資料庫的 owner，
-- 因而隱含屬於 pg_database_owner（pg_has_role 回 true 已確認），
-- 由該群組取得 UC。它對 schema public 是實質 owner，REVOKE 下得動，
-- 且自己的 CREATE 不受影響 —— Flyway 日後照樣建得了表。
REVOKE CREATE ON SCHEMA public FROM PUBLIC;


-- ===================================================================
-- 第三部分：既有物件的表層權限
-- ===================================================================
-- 刻意不用 GRANT ... ON ALL TABLES IN SCHEMA public：那會一併蓋到
-- flyway_schema_history（要另外處理），而且出錯時看不出卡在哪一張表。
--
-- 授與的四項就是 DML 的全部，刻意不含：
--   TRUNCATE  —— 一句話清空整表，且不受 RLS policy 約束，正是要防的動作
--   REFERENCES —— 建外鍵屬於 DDL
--   TRIGGER    —— 同上
--   MAINTAIN   —— PostgreSQL 17 新增，VACUUM／ANALYZE／REINDEX 等維運動作
DO $$
DECLARE
    tbl RECORD;
BEGIN
    FOR tbl IN
        SELECT c.relname, c.relkind
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
          AND c.relname <> 'flyway_schema_history'
    LOOP
        IF tbl.relkind = 'm' THEN
            -- 具體化檢視的內容由 REFRESH 產生，寫不進去，給 SELECT 就夠
            EXECUTE format('GRANT SELECT ON public.%I TO ssds_app', tbl.relname);
        ELSE
            EXECUTE format(
                'GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO ssds_app', tbl.relname);
        END IF;
    END LOOP;
END $$;

-- Flyway 帳本：只給 SELECT。
-- 讀得到是有意義的（組員能自己確認共用庫套到第幾版、checksum 對不對），
-- 寫不進去才是重點 —— 2026-08-21 的事故就是有人 DELETE 了這張表的一列。
-- 鎖層級：V12 已實測 REVOKE 在 migration 執行中對本表不會互等；
-- GRANT 與 REVOKE 走同一條路徑、取同一種鎖，故沿用。
-- （這是依 V12 的實測結果推得，不是對 GRANT 另外實測過的結論。）
GRANT SELECT ON public.flyway_schema_history TO ssds_app;

-- sequence：INSERT 若依賴 GENERATED／DEFAULT nextval()，沒有 USAGE 會直接失敗。
-- SELECT 給的是 currval／lastval 的讀取能力；不給 UPDATE（那能重設序號）。
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ssds_app;


-- ===================================================================
-- 第四部分：逐表補 RLS policy（理由見檔頭）
-- ===================================================================
-- 只有 r／p 需要：檢視不受 RLS 約束（走的是檢視 owner 的權限），
-- flyway_schema_history 則從未啟用 RLS，它的防線是上面的「只給 SELECT」。
DO $$
DECLARE
    tbl RECORD;
BEGIN
    FOR tbl IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          AND c.relkind IN ('r', 'p')
          AND c.relname <> 'flyway_schema_history'
          AND NOT EXISTS (
              SELECT 1 FROM pg_policy pol
              WHERE pol.polrelid = c.oid
                AND pol.polname = 'p_ssds_app_rw'
          )
    LOOP
        EXECUTE format(
            'CREATE POLICY p_ssds_app_rw ON public.%I FOR ALL TO ssds_app '
            'USING (true) WITH CHECK (true)', tbl.relname);
    END LOOP;
END $$;


-- ===================================================================
-- 第五部分：未來新建物件的預設權限
-- ===================================================================
-- ALTER DEFAULT PRIVILEGES 只影響「由執行本檔的角色」日後建立的物件，
-- 也就是 Flyway 以 postgres 建的表 —— 正是要涵蓋的範圍。
-- 由 Supabase Dashboard 以 supabase_admin 建立的物件不在此列。
--
-- 再說一次檔頭那件事：這一段只補得上「表層 GRANT」。
-- 新表的 RLS policy 沒有任何自動機制，必須在該支 migration 內手寫。
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ssds_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ssds_app;
