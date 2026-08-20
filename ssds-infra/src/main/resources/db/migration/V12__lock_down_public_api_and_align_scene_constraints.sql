-- ===================================================================
-- V12 關閉 public schema 的對外 API 暴露，並補上 V7 未帶到的約束
--
-- 背景（安全性）：
-- Supabase 專案預設會把 public schema 的每一張表透過 PostgREST 開成對外
-- HTTP API，由 anon / authenticated 兩個角色存取。anon key 依設計是公開值
-- （正常用法直接寫在前端 JS 裡），本身不構成保護，真正的把關是 RLS。
-- 本專案前端為 Angular、不使用 supabase-js，後端以 JDBC 用 table owner
-- （postgres）連線，這兩個角色完全沒有被使用，卻握有 public 下每張表的
-- SELECT / INSERT / UPDATE / DELETE / TRUNCATE 權限且 RLS 全數關閉 ——
-- 任何取得專案 URL 與 anon key 的人可以讀光，也可以清空整個資料庫。
--
-- 本檔做三層防護：
--   第一層：收回 anon / authenticated 的權限，並改掉 default privileges，
--           使日後新建的表不會再被自動授權。
--   第一層之二：收回偽角色 PUBLIC 的函式 EXECUTE。函式的預設授權對象是
--           PUBLIC 而非個別角色，逐角色 REVOKE 收不到，必須另外處理。
--   第二層：對 public 既有資料表啟用 RLS 且不建立任何 policy（預設全拒）。
--
-- 為什麼不會弄壞後端：public 下所有表的 owner 都是 postgres，且該角色的
-- rolbypassrls 為 true；RLS 對 owner 與 BYPASSRLS 角色不生效（除非另外下
-- FORCE ROW LEVEL SECURITY，本檔不使用）。Flyway 與應用連線皆走此角色。
--
-- 相容性：anon / authenticated 是 Supabase 專有角色，一般 PostgreSQL
-- （例如 Testcontainers 起的容器）沒有這兩個角色，故先查 pg_roles，
-- 缺角色時整段略過，讓本檔在乾淨的 PostgreSQL 上也能跑完。
--
-- ！！ flyway_schema_history 只能排除在第二層之外 ！！
-- Flyway 執行 migration 時，會在另一條連線上開著交易並持有
-- flyway_schema_history 的鎖。對該表下 ALTER TABLE（需要 ACCESS EXCLUSIVE）
-- 會與那條連線互等 —— 不是變慢，是整支 migration 停在那裡不會結束。
-- 因此第二層的 RLS 略過該表。
-- 第一層的 REVOKE 則實測不受影響（鎖層級較弱），該表照收，
-- 收乾淨後 anon / authenticated 對它沒有任何權限，等同被保護。
--
-- 附帶一提，本檔的 REVOKE ... ON SCHEMA public 只收得掉「直接授與該角色」
-- 的 USAGE，收不掉 PostgreSQL 預設授與偽角色 PUBLIC 的那份，
-- 所以 has_schema_privilege() 事後仍會回報 true。保護是靠表層權限與 RLS，
-- 不是靠 schema USAGE —— 不要把那一行當成防線。
-- ===================================================================


-- ===================================================================
-- 鎖等待上限
-- ===================================================================
-- 本檔會對大量既有資料表下 DDL，只要有任一條連線壓著鎖就會卡住。
-- 沒有這行的話症狀是「migration 永遠不結束」，看不出原因；設了之後
-- 會在 30 秒內以 lock_timeout 失敗並指出卡在哪一張表，可以直接重跑。
-- SET LOCAL 只在本次 migration 的交易內有效，不影響其他連線。
SET LOCAL lock_timeout = '30s';


-- ===================================================================
-- 第一層：收回 Supabase API 角色的權限
-- ===================================================================

DO $$
DECLARE
    api_role TEXT;
    tbl      RECORD;
BEGIN
    FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
            CONTINUE;
        END IF;

        -- 逐表收回。用 ON ALL TABLES 也可以，寫成迴圈是為了在出錯時
        -- 知道卡在哪一張表（配合上面的 lock_timeout）
        FOR tbl IN
            SELECT c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
        LOOP
            EXECUTE format('REVOKE ALL ON public.%I FROM %I', tbl.relname, api_role);
        END LOOP;

        EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I', api_role);
        EXECUTE format('REVOKE ALL ON ALL ROUTINES IN SCHEMA public FROM %I', api_role);

        -- 收掉 Supabase 直接授與的 schema USAGE。這不是防線（PUBLIC 的那份
        -- 收不掉，見檔頭），只是減少一個不必要的授權
        EXECUTE format('REVOKE ALL ON SCHEMA public FROM %I', api_role);

        -- 日後新建的物件。ALTER DEFAULT PRIVILEGES 只影響「由目前角色建立」
        -- 的物件，也就是 Flyway 之後所建的表 —— 這正是我們要涵蓋的範圍。
        -- （由 Supabase Dashboard 以 supabase_admin 建立的物件不在此列，
        --   需要時得另外處理。）
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM %I', api_role);
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM %I', api_role);
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON ROUTINES FROM %I', api_role);
    END LOOP;
END $$;


-- ===================================================================
-- 第一層之二：收回偽角色 PUBLIC 的函式 EXECUTE
-- ===================================================================
-- 上面的逐角色 REVOKE 收不掉這一份。PostgreSQL 對新建函式的內建預設是把
-- EXECUTE 授給偽角色 PUBLIC，而每個角色都隱含繼承 PUBLIC 的授權，
-- 因此 anon / authenticated 仍可呼叫 public 底下的函式 ——
-- Supabase 的 RPC 端點走的就是這條路。
--
-- 實測（PostgreSQL 17）：
--   ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ... FROM PUBLIC → 無效
--   ALTER DEFAULT PRIVILEGES            REVOKE ... FROM PUBLIC → 有效
-- 也就是不能加 IN SCHEMA，必須用不限定 schema 的形式。
--
-- 取捨：不限定 schema 代表 postgres 日後在「任何 schema」建立的函式都不再
-- 自動授與 PUBLIC EXECUTE。本專案只在 public 建立物件，可以接受；
-- 但日後若把擴充套件裝進 public，其函式需要明確 GRANT 才能被非 owner 呼叫。
--
-- 現況：套用當下 public 底下沒有任何函式（已查證），故第一行是空操作，
-- 真正生效的是第二行對未來函式的預設。
REVOKE EXECUTE ON ALL ROUTINES IN SCHEMA public FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON ROUTINES FROM PUBLIC;


-- ===================================================================
-- 第二層：對 public 既有資料表啟用 RLS（不建 policy＝預設全拒）
-- ===================================================================
-- 排除 flyway_schema_history：對它下 ALTER TABLE 會與 Flyway 自己的
-- 交易互等而永久卡住（見檔頭說明）。該表已在第一層收乾淨權限。
-- 不建立任何 policy 是刻意的 —— 目前沒有任何走 PostgREST 的使用情境，
-- 需要開放時應該針對個別表寫明確的 policy，而不是整片放行。

DO $$
DECLARE
    tbl RECORD;
BEGIN
    FOR tbl IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          -- 'p' = partitioned table。父表的 RLS 不會下傳到分割區，
          -- 分割區本身是 'r'，兩者都要各自啟用
          AND c.relkind IN ('r', 'p')
          AND c.relname <> 'flyway_schema_history'
          AND NOT c.relrowsecurity
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', tbl.relname);
    END LOOP;
END $$;


-- ===================================================================
-- 第三部分：補上 V7 未帶到的約束
-- ===================================================================
-- V7～V11 已套用至共用資料庫，checksum 已鎖定，不能回頭修改，
-- 因此缺漏的約束一律以新版本補上。

-- heat_bucket 的值域。V7 只給了 NOT NULL DEFAULT 'UNKNOWN' 卻沒有 CHECK，
-- 與同一支檔案其他情境欄位（都有 CHECK）不一致，任何字串都寫得進去。
-- 值域取自 ssds-ai 的 HeatBucket enum，順序即百分位由低到高。
ALTER TABLE scene_classification_log
    ADD CONSTRAINT ck_scene_heat_bucket CHECK (
        heat_bucket IN ('UNKNOWN', 'VERY_LOW', 'LOW', 'MEDIUM', 'HIGH', 'VERY_HIGH')
    );

-- 降級一致性（§5.4：Schema 驗證失敗或 confidence < 0.5 時 ai_scene_type 為 NULL）。
-- 只約束「fallback_applied 為真 ⟹ ai_scene_type 為 NULL」這一個方向。
-- 反方向刻意不加：共用庫上有 1 筆 V7 之前寫入的舊資料為
-- ai_scene_type IS NULL 且 fallback_applied = FALSE，加了會讓本檔套用失敗。
ALTER TABLE scene_classification_log
    ADD CONSTRAINT ck_scene_fallback_implies_no_ai_type CHECK (
        NOT fallback_applied OR ai_scene_type IS NULL
    );

COMMENT ON COLUMN scene_classification_log.heat_bucket IS
    '七日快取 key 的熱度區間；同品項跨區間時必須重新判定。'
    '值域見 ssds-ai 的 HeatBucket enum，由 percentile_within_source 分箱而來。'
    'V7 之前寫入的舊列一律為 UNKNOWN，代表「當時無此概念」而非「查無熱度」';


-- ===================================================================
-- 第四部分：以 COMMENT 覆寫已失效的舊說明
-- ===================================================================
-- 已套用的 migration 檔案本身不能改（checksum），但 COMMENT 可以覆寫。

COMMENT ON COLUMN ai_task_item.raw_response IS
    '【已由 ai_attempt.raw_response 取代，勿再寫入】'
    'V11 起每次模型呼叫（含重試與備援）各留一列 ai_attempt，原始回應存於該表；'
    '本欄僅保留 V11 之前的歷史資料，新流程不應再使用';
