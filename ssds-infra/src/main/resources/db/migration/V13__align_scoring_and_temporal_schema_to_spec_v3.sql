-- ===================================================================
-- V13：將評分與時序相關 schema 對齊規格書 v3.0
-- ===================================================================
-- 對應 FR-04（選品分數排行）、FR-08（情境權重組設定）、FR-17（節慶檔期與氣候基準）。
--
-- 【本檔含破壞性變更，已與全組協調】
-- 第零部分的情境代碼改名與第二部分的 period 型別變更，會讓沿用舊名稱／
-- 舊長度的程式在執行期或啟動時失敗。套用前必須完成：
--   1. ssds-core 的 SceneType 列舉改為 VIRAL / FESTIVAL / REPLENISHMENT / SEASONAL
--   2. ProductScore.period 的對映改為 CHAR(7)
--   3. db/dev 的 V902、V904 假資料改用新代碼，並將其 flyway_schema_history
--      的 checksum 設為 NULL（見檔末「套用程序」）
--   4. 各分支上引用 SceneType 的程式一併調整
--
-- 【刻意不做】舊索引 idx_score_period_grade 不刪除。v3.0 說「索引改為
-- idx_score_rank」，但刪索引只會讓別人的查詢默默變慢、不會報錯，
-- 屬於最難追查的一類問題。待四個功能收斂後另行清理。


-- ===================================================================
-- 第零部分：情境代碼改名為 v3.0 值域
-- ===================================================================
--   VIRAL_TOPIC    → VIRAL
--   STAPLE_RESTOCK → REPLENISHMENT
--   FESTIVAL、SEASONAL 不變
--
-- 【背景】V1 與 V4 當初實作的是 v2.0 舊版的值域
-- （VIRAL_TOPIC / FESTIVAL / STAPLE_RESTOCK / SEASONAL），忠於當時的規格。
-- 規格書作者後續同時修訂了 v2.0 與 v3.0，將對外代碼改為
-- VIRAL / FESTIVAL / REPLENISHMENT / SEASONAL，但未公告。
-- 兩份現行規格書（v2.0 修訂版、v3.0）皆已統一為新代碼，
-- 全組決議資料庫端一併改名，不保留應用層轉接。
--
-- 涉及的欄位共六個 CHECK，分布於四張表：
--   weight_profile.scene_type                  （V1）
--   product_score.scene_type                   （V1）
--   scene_classification_log.ai_scene_type     （V4）
--   scene_classification_log.final_scene_type  （V4）
--   scene_classification_log.alternative_scene_type （V7）
--   campaign_snapshot.scene_type               （V5）

-- 步驟 1：先移除既有 CHECK，否則 UPDATE 會被舊值域擋下。
--
-- V1／V4／V5／V7 的 CHECK 都是內嵌宣告、未具名，實際名稱由 PostgreSQL
-- 自動配發（如 product_score_scene_type_check），且不同資料庫上可能因
-- 建立順序而帶不同流水號。因此以「約束定義中含 STAPLE_RESTOCK」為條件
-- 動態尋找，不寫死名稱。
DO $$
DECLARE
    con RECORD;
BEGIN
    FOR con IN
        SELECT rel.relname AS table_name, c.conname AS constraint_name
        FROM pg_constraint c
                 JOIN pg_class rel ON rel.oid = c.conrelid
                 JOIN pg_namespace n ON n.oid = rel.relnamespace
        WHERE n.nspname = 'public'
          AND c.contype = 'c'
          AND pg_get_constraintdef(c.oid) LIKE '%STAPLE_RESTOCK%'
    LOOP
        EXECUTE format('ALTER TABLE public.%I DROP CONSTRAINT %I',
                       con.table_name, con.constraint_name);
        RAISE NOTICE 'V13 已移除舊情境值域約束：%.%', con.table_name, con.constraint_name;
    END LOOP;
END $$;

-- 步驟 2：改寫既有資料。
-- 每一句都寫成冪等（只更新仍為舊值的列），重跑不會出錯。
UPDATE weight_profile
SET scene_type = CASE scene_type
                     WHEN 'VIRAL_TOPIC' THEN 'VIRAL'
                     WHEN 'STAPLE_RESTOCK' THEN 'REPLENISHMENT' END
WHERE scene_type IN ('VIRAL_TOPIC', 'STAPLE_RESTOCK');

UPDATE product_score
SET scene_type = CASE scene_type
                     WHEN 'VIRAL_TOPIC' THEN 'VIRAL'
                     WHEN 'STAPLE_RESTOCK' THEN 'REPLENISHMENT' END
WHERE scene_type IN ('VIRAL_TOPIC', 'STAPLE_RESTOCK');

UPDATE campaign_snapshot
SET scene_type = CASE scene_type
                     WHEN 'VIRAL_TOPIC' THEN 'VIRAL'
                     WHEN 'STAPLE_RESTOCK' THEN 'REPLENISHMENT' END
WHERE scene_type IN ('VIRAL_TOPIC', 'STAPLE_RESTOCK');

-- scene_classification_log 三個欄位分開處理。
-- ai_scene_type 與 alternative_scene_type 可為 NULL（§5.4 降級時為 NULL），
-- WHERE 的 IN 判斷本身就會濾掉 NULL，不需另外處理。
UPDATE scene_classification_log
SET ai_scene_type = CASE ai_scene_type
                        WHEN 'VIRAL_TOPIC' THEN 'VIRAL'
                        WHEN 'STAPLE_RESTOCK' THEN 'REPLENISHMENT' END
WHERE ai_scene_type IN ('VIRAL_TOPIC', 'STAPLE_RESTOCK');

UPDATE scene_classification_log
SET final_scene_type = CASE final_scene_type
                           WHEN 'VIRAL_TOPIC' THEN 'VIRAL'
                           WHEN 'STAPLE_RESTOCK' THEN 'REPLENISHMENT' END
WHERE final_scene_type IN ('VIRAL_TOPIC', 'STAPLE_RESTOCK');

UPDATE scene_classification_log
SET alternative_scene_type = CASE alternative_scene_type
                                 WHEN 'VIRAL_TOPIC' THEN 'VIRAL'
                                 WHEN 'STAPLE_RESTOCK' THEN 'REPLENISHMENT' END
WHERE alternative_scene_type IN ('VIRAL_TOPIC', 'STAPLE_RESTOCK');

-- 步驟 3：以新值域重建 CHECK，這次全部具名，日後要動就不必再靠動態尋找。
ALTER TABLE weight_profile
    ADD CONSTRAINT ck_weight_profile_scene CHECK (
        scene_type IN ('VIRAL', 'FESTIVAL', 'REPLENISHMENT', 'SEASONAL'));

ALTER TABLE product_score
    ADD CONSTRAINT ck_score_scene CHECK (
        scene_type IN ('VIRAL', 'FESTIVAL', 'REPLENISHMENT', 'SEASONAL'));

ALTER TABLE campaign_snapshot
    ADD CONSTRAINT ck_campaign_snapshot_scene CHECK (
        scene_type IN ('VIRAL', 'FESTIVAL', 'REPLENISHMENT', 'SEASONAL'));

ALTER TABLE scene_classification_log
    ADD CONSTRAINT ck_scene_log_ai_scene CHECK (
        ai_scene_type IN ('VIRAL', 'FESTIVAL', 'REPLENISHMENT', 'SEASONAL')),
    ADD CONSTRAINT ck_scene_log_final_scene CHECK (
        final_scene_type IN ('VIRAL', 'FESTIVAL', 'REPLENISHMENT', 'SEASONAL')),
    ADD CONSTRAINT ck_scene_log_alternative_scene CHECK (
        alternative_scene_type IN ('VIRAL', 'FESTIVAL', 'REPLENISHMENT', 'SEASONAL'));

COMMENT ON COLUMN scene_classification_log.ai_scene_type IS
    'AI 判定結果。信心值低於 0.5 或 Schema 驗證失敗時本欄為 NULL，'
    'final_scene_type 退回 REPLENISHMENT（§5.4）。'
    'V13 前本欄值域為 v2.0 舊版的 VIRAL_TOPIC / STAPLE_RESTOCK';


-- ===================================================================
-- 第一部分：grade_threshold（v3.0 新增，§FR-08、§7.2.5）
-- ===================================================================
-- 四榜各自的 A／B 分級門檻，綁定 weight_version。
--
-- 為什麼不沿用 weight_version.grade_a_threshold／grade_b_threshold：
-- 那是一對純量，全系統只有一組門檻。v3.0 §FR-08 要求四榜各自設定
-- （話題爆款 85/70、節慶檔期 85/70、常態補貨 80/65、季節導向 82/68），
-- 一對純量承載不了四組值。
--
-- 為什麼綁 version_id：門檻與權重同屬評分規則。AC-08-4 要求每筆評分
-- 都能回溯「當時的權重設定與該版本的分級門檻」，兩者必須一起版本化，
-- 否則改了門檻就再也重現不出歷史分級（G-5 可追溯性）。

CREATE TABLE grade_threshold (
    version_id  BIGINT        NOT NULL REFERENCES weight_version (id) ON DELETE CASCADE,
    scene_type  VARCHAR(24)   NOT NULL
                CHECK (scene_type IN ('VIRAL', 'FESTIVAL', 'REPLENISHMENT', 'SEASONAL')),
    grade_a_min DECIMAL(5, 2) NOT NULL,
    grade_b_min DECIMAL(5, 2) NOT NULL,

    CONSTRAINT pk_grade_threshold PRIMARY KEY (version_id, scene_type),
    -- A 級門檻必須高於 B 級，否則分級規則無解
    CONSTRAINT ck_grade_threshold_order CHECK (grade_a_min > grade_b_min),
    -- 分數值域為 0–100（§5.5：加分小計上限 100）
    CONSTRAINT ck_grade_threshold_range CHECK (
        grade_b_min >= 0 AND grade_a_min <= 100
    )
);

COMMENT ON TABLE grade_threshold IS
    '四榜（情境）各自的 A／B 分級門檻，隨 weight_version 一併版本化（§FR-08、AC-08-6）。'
    '門檻維度為「榜」而非「品類」——v2.0 兩種說法並存，v3.0 裁決為榜（§15 C-11）';
COMMENT ON COLUMN grade_threshold.grade_a_min IS 'A 級下限（含）';
COMMENT ON COLUMN grade_threshold.grade_b_min IS 'B 級下限（含）；低於此值為 C 級';

-- 既有權重版本回填四榜門檻。
--
-- 刻意沿用該版本原本的純量門檻，而不是套用 v3.0 §FR-08 的四組建議值
-- （85/70、85/70、80/65、82/68）。理由：既有版本上已經掛著已評完的
-- product_score，那些 grade 是用舊門檻算出來的。若在此塞入新門檻，
-- 歷史評分就再也重現不出當初的分級，正好違反這張表要保障的 G-5。
-- 四組建議值屬於「新版本該長什麼樣」，由應用層建立草稿版本時帶入。
INSERT INTO grade_threshold (version_id, scene_type, grade_a_min, grade_b_min)
SELECT v.id,
       s.scene_type,
       v.grade_a_threshold::DECIMAL(5, 2),
       v.grade_b_threshold::DECIMAL(5, 2)
FROM weight_version v
         CROSS JOIN (VALUES ('VIRAL'), ('FESTIVAL'),
                            ('REPLENISHMENT'), ('SEASONAL')) AS s(scene_type);

-- 舊的純量門檻欄位不刪除（別人的 Entity 還對映著它），改以 COMMENT 標示作廢。
COMMENT ON COLUMN weight_version.grade_a_threshold IS
    '【已由 grade_threshold 取代，勿再讀取】'
    'V13 起分級門檻依榜設定，四榜各一列存於 grade_threshold。'
    '本欄僅保留 V13 之前的歷史值，且已回填至 grade_threshold 的四列';
COMMENT ON COLUMN weight_version.grade_b_threshold IS
    '【已由 grade_threshold 取代，勿再讀取】說明同 grade_a_threshold';


-- ===================================================================
-- 第二部分：product_score 對齊 §7.2.6
-- ===================================================================

-- --- 2a. 多情境評分所需的兩個旗標（§FR-04）---
-- v3.0 §FR-04 裁決「一個品項在一個 period 內，每個適用情境各產生一筆
-- product_score」，用以支撐「同一品項可同時出現在多張榜」。
-- V1 的 product_score 既無 is_primary 也無唯一鍵，同一品項同期可無限
-- 重複評分，且無法辨識哪一筆是主情境、哪一筆是最新有效值。
--
-- DEFAULT true 是刻意的：既有資料每個 (product_id, period) 只有單一情境，
-- 那一筆本來就是主情境，也必然是最新有效值。
ALTER TABLE product_score
    ADD COLUMN is_primary BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN is_active  BOOLEAN NOT NULL DEFAULT true;

COMMENT ON COLUMN product_score.is_primary IS
    '主情境那筆為 true，次要情境為 false（§FR-04 多情境評分）。'
    'SceneClassifierAgent 的 sceneType 為主情境，alternativeScene 為次要情境。'
    'FR-05 品項詳情預設顯示主情境；FR-11 決策綁定的也是主情境那筆';
COMMENT ON COLUMN product_score.is_active IS
    '同 (product_id, period, scene_type) 重複評分時僅最新一筆為 true（§5.10）。'
    '舊紀錄保留不刪除，排行查詢須自行過濾 is_active = true';

-- 回填 is_active：同鍵之下只留最新的 calculated_at 為 true。
-- calculated_at 相同時以 id 較大者為新，避免同秒寫入造成兩筆都是 true。
UPDATE product_score s
SET is_active = false
WHERE EXISTS (SELECT 1
              FROM product_score newer
              WHERE newer.product_id = s.product_id
                AND newer.period = s.period
                AND newer.scene_type = s.scene_type
                AND (newer.calculated_at > s.calculated_at
                    OR (newer.calculated_at = s.calculated_at AND newer.id > s.id)));

-- --- 2b. period 由 VARCHAR(8) 改為 CHAR(7)（§7.2.6）---
-- §7.2.6 指定 CHAR(7)。ISO 週字串如 '2026W30' 恰為 7 字元。
--
-- 註：v3.0 給的理由是「CHAR(8) 會補尾隨空白造成比對失敗」，而本專案 V1
-- 用的是 VARCHAR(8)（不補位），該症狀原本並不存在。此處仍改為 CHAR(7)
-- 是為了與規格書逐字一致，並讓長度本身成為格式的第一道防線。
--
-- rtrim 是防禦性的：VARCHAR(8) 理論上不會有尾隨空白，但若曾有程式寫入
-- 補過空白的值，直接轉型會因超長而失敗。先修掉再轉。
ALTER TABLE product_score
    ALTER COLUMN period TYPE CHAR(7) USING rtrim(period);

-- 格式約束：四位西元年 + 'W' + 兩位週次（01–53）。
-- V1 只有 NOT NULL，任何 8 字元以內的字串都寫得進去。
ALTER TABLE product_score
    ADD CONSTRAINT ck_score_period_format CHECK (
        period ~ '^[0-9]{4}W(0[1-9]|[1-4][0-9]|5[0-3])$');

COMMENT ON COLUMN product_score.period IS
    'ISO 週，如 2026W30，以 Asia/Taipei 判定（§7.2.6）。'
    'V13 起為 CHAR(7) 並帶格式 CHECK；V13 之前為 VARCHAR(8) 且無格式約束';

-- --- 2c. 唯一鍵與索引（§7.2.6）---
-- 唯一鍵含 calculated_at。
--
-- 注意 §FR-04 的表格寫的是三欄的 uk_score(product_id, period, scene_type)，
-- 與 §7.2.6 的四欄版本不一致。此處依 §7.2.6 採四欄，因為三欄版本會讓
-- §5.10 的「每次評分產生新紀錄、保留歷史、不覆寫」變成不可能，
-- 也會讓 is_active 這個欄位失去存在意義。三欄版本應為 §FR-04 的筆誤。
CREATE UNIQUE INDEX uk_score
    ON product_score (product_id, period, scene_type, calculated_at);

-- §7.2.6 指定索引：排行查詢依榜、只取有效列
CREATE INDEX idx_score_rank
    ON product_score (period, scene_type, is_active, grade, final_score DESC);
-- §7.2.6 指定索引：品項詳情取該期主情境
CREATE INDEX idx_score_product
    ON product_score (product_id, period, is_primary);

-- 「同鍵僅最新一筆 is_active」的資料庫層保證。
--
-- 規格書把這件事寫成敘述（§5.10、§7.2.6），沒有指定約束。但 uk_score 含
-- calculated_at，所以同一 (品項, 期別, 情境) 本來就允許多列；而
-- is_active 的 DEFAULT 是 true。兩者相加的結果是：只要評分流程漏做
-- 「先把舊列改為 false」，就會出現兩筆 is_active = true，
-- 而排行查詢會把同一品項顯示兩次——不會報錯，只會顯示錯。
--
-- 這個 partial unique index 把它從「Service 要記得做」變成「資料庫保證」。
--
-- 【寫入順序要求】新增評分時必須在同一交易內先 UPDATE 舊列為 false，
-- 再 INSERT 新列。順序顛倒會撞這個索引。
-- （partial index 無法宣告為 DEFERRABLE，UNIQUE 約束又不支援 WHERE，
--   所以沒有「交易結束才檢查」的選項，只能靠順序。）
CREATE UNIQUE INDEX uk_score_active
    ON product_score (product_id, period, scene_type)
    WHERE is_active;

-- 「同品項同一期只有一個主情境」的資料庫層保證。
--
-- uk_score_active 管的是「每個情境一筆有效」，管不到跨情境：品項 101
-- 在同一期可以同時有 VIRAL 與 SEASONAL 兩筆 is_active，這正是 §FR-04
-- 多情境評分要的；但其中只能有一筆 is_primary（§FR-04「主情境的那筆
-- is_primary = true，其餘為 false」）。
--
-- 沒有這道約束時，兩筆都是 primary 也不會報錯，而 §FR-05 品項詳情、
-- 儀表板 KPI 去重、§FR-11-1 決策綁定全部靠 is_primary 取「那一筆」，
-- 會各自取到不同的列。
--
-- 【寫入順序要求】人工覆寫情境時（§FR-04「覆寫後被指定的情境成為主情境，
-- 原主情境降為次要，不刪除」）須在同一交易內先把舊主情境改為 false，
-- 再把新主情境改為 true。理由同上一個索引：partial index 無法 DEFERRABLE。
CREATE UNIQUE INDEX uk_score_active_primary
    ON product_score (product_id, period)
    WHERE is_active AND is_primary;

COMMENT ON COLUMN product_score.scene_type IS
    '套用的情境權重組，值域 VIRAL / FESTIVAL / REPLENISHMENT / SEASONAL（§7.2.6）。'
    'V13 之前為 v2.0 舊版的 VIRAL_TOPIC / FESTIVAL / STAPLE_RESTOCK / SEASONAL';


-- ===================================================================
-- 第三部分：氣候適配因子的輸入欄位（§FR-17-2、§7.2）
-- ===================================================================
-- v2.0 的氣候因子只有「8 月均溫 29.4°C」這個原始值範例，卻沒有任何欄位
-- 記錄「這個品項適合什麼溫度」，因子在 v2.0 是算不出來的（§15 B-15）。
-- v3.0 定義：Tmin ≤ T ≤ Tmax 時 fit = 1，否則 max(0, 1 − distance / tolerance)。

ALTER TABLE product
    ADD COLUMN ideal_temp_min DECIMAL(4, 1),
    ADD COLUMN ideal_temp_max DECIMAL(4, 1);

-- 只約束「兩個都填時 min ≤ max」。刻意允許單邊填寫與兩邊皆空：
-- §FR-17-2 規定品項未填時沿用品類預設，兩者皆無則該因子標為無資料
-- （不扣分，權重依 §5.7 分攤），因此 NULL 是合法且有意義的狀態。
ALTER TABLE product
    ADD CONSTRAINT ck_product_ideal_temp_order CHECK (
        ideal_temp_min IS NULL OR ideal_temp_max IS NULL
            OR ideal_temp_min <= ideal_temp_max
    );

COMMENT ON COLUMN product.ideal_temp_min IS
    '品項適溫區間下限（°C），供 CLIMATE 因子（§FR-17-2）。'
    'NULL 表示未填，改用 category_climate_profile 的品類預設';
COMMENT ON COLUMN product.ideal_temp_max IS
    '品項適溫區間上限（°C）。說明同 ideal_temp_min';

CREATE TABLE category_climate_profile (
    category_id    BIGINT        PRIMARY KEY REFERENCES category (id) ON DELETE CASCADE,
    ideal_temp_min DECIMAL(4, 1) NOT NULL,
    ideal_temp_max DECIMAL(4, 1) NOT NULL,
    -- §FR-17-2：TOLERANCE 預設 12°C，可由 SYS_ADMIN 調整
    tolerance      DECIMAL(4, 1) NOT NULL DEFAULT 12.0,

    CONSTRAINT ck_climate_profile_order CHECK (ideal_temp_min <= ideal_temp_max),
    CONSTRAINT ck_climate_profile_tolerance CHECK (tolerance > 0)
);

COMMENT ON TABLE category_climate_profile IS
    '品類層級的預設適溫區間。品項未填 ideal_temp_min/max 時沿用此值（§FR-17-2）。'
    '本表與品項皆無資料時，CLIMATE 因子標為無資料，不扣分，權重依 §5.7 分攤';
COMMENT ON COLUMN category_climate_profile.tolerance IS
    '適配度衰減的容忍範圍（°C）。fit = max(0, 1 − distance / tolerance)，預設 12.0';


-- ===================================================================
-- 第四部分：對新表啟用 RLS，與 V12 的安全姿態保持一致
-- ===================================================================
-- V12 以迴圈對「當時已存在」的 public 資料表啟用 RLS，但
-- ALTER DEFAULT PRIVILEGES 管不到 RLS —— 新建的表預設不啟用。
-- 因此每一支新增資料表的 migration 都必須自己補這一段，否則新表會是
-- 整個 schema 裡唯一沒有 RLS 的破口。
-- ssds-infra 的 MigrationVerificationTest 會驗證這件事。
--
-- 與 V12 同樣不建立任何 policy：不建 policy 即為預設全拒。
-- 應用連線走的角色具 BYPASSRLS，不受影響。

ALTER TABLE grade_threshold          ENABLE ROW LEVEL SECURITY;
ALTER TABLE category_climate_profile ENABLE ROW LEVEL SECURITY;


-- ===================================================================
-- 套用程序（給負責套用的人）
-- ===================================================================
-- 本檔的第零、二部分會改動既有資料與型別，順序不能顛倒：
--
--   1. 先在共用資料庫執行檔案 db/dev/README 中列出的前置檢查查詢，
--      確認沒有 uk_score 重複列、沒有不符 ck_score_period_format 的 period、
--      也沒有本檔未涵蓋的舊代碼殘留。
--   2. 全組 pull 到含本檔、新版 SceneType、新版 ProductScore 與
--      改過的 V902／V904 的 commit。
--   3. 在共用資料庫執行：
--        UPDATE flyway_schema_history SET checksum = NULL
--        WHERE version IN ('902', '904');
--      V902／V904 的內容因改名而變動，checksum 與已套用紀錄對不上。
--      Flyway 對 checksum 為 NULL 的紀錄會跳過比對
--      （MigrationInfo#isChecksumMatching：applied checksum 為 null 即視為相符），
--      這是不用 CLI repair 的等效做法。
--      注意 ignore-migration-patterns 不涵蓋 checksum，這一步不能省。
--   4. 啟動應用，Flyway 套用本檔。
--
-- 【V902／V904 的 checksum：對其他資料庫的說明】
--
-- 第 3 步只處理共用資料庫。任何「已經套用過舊版 V902／V904」的資料庫
-- 都需要同一步，否則啟動會停在：
--     Migration checksum mismatch for migration version 902
-- ignore-migration-patterns 只放行 missing 與 future，不涵蓋 checksum。
--
-- 就本專案的設定而言，這樣的資料庫只有共用資料庫一個：
--   - .env.example 只提供 SUPABASE_* 一組連線參數，指向共用專案
--   - 全 repo 的 properties／yml／gradle 都沒有 localhost:5432 或 h2 的設定
--   - MigrationVerificationTest 每次以 Testcontainers 起全新容器，
--     schema_history 是空的，不存在 checksum 比對
--
-- 但若有人把自己的 .env 指向個人的 Supabase 專案並跑過舊版 V902／V904，
-- 那個資料庫就需要處理。兩條路擇一：
--
--   A. 保留資料：在該資料庫執行與第 3 步相同的 UPDATE，再啟動。
--   B. 不要資料：直接把 public schema 砍掉重建，讓 Flyway 從 V1 重跑。
--      開發資料庫本來就是可拋棄的（見 application-dev.properties 對
--      out-of-order 的說明）。
--
-- 不能改回舊版 V902／V904 了事：乾淨建置的順序是 V13 → V900–V905，
-- V13 已把 CHECK 換成新情境代碼，舊版 V902 會在新 CHECK 下插入失敗。
