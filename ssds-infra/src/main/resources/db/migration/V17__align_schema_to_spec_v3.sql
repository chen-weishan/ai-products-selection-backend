-- ===================================================================
-- V17：將共用資料庫完全對齊《開發規格書 v3.0》§7.2
-- ===================================================================
-- 逐表稽核結果與每一條裁決另有完整工作紀錄（未進版控），需要時向 schema 維護者索取。
--
-- 【本檔含大量破壞性變更，套用前必須完成 Java 端同步】
-- 至少下列對映會失效，ddl-auto=validate 會讓服務啟動失敗：
--   1. FactorCode：HEAT_SLOPE→TREND、CONVERSION→CVR、移除 HEAT_VOLUME
--   2. WeightVersionStatus：ACTIVE→APPROVED
--   3. WeightVersion：刪兩個純量門檻欄，加 is_current／source_calibration_id
--   4. TrendDaily／DecisionFeedback 兩組 entity + repository 必須刪除（表被 drop）
--   5. 其餘 20 張表的欄位增刪，見本檔第二部分
--
-- 【裁決原則】（audit.md §4.5，Vincent 2026-08-23 定案）
--   只刪規格書「明文」寫已移除／已廢除的欄位與表。
--   規格書欄位表未列、但實作已存在的欄位一律保留，視為實作擴充。
--   因此 trend_interpretation、ai_attempt、ai_attempt.raw_response、
--   calibration_report 的七個欄位、sourcing_candidate 的 AI 欄位等全部保留。
--
-- 【方言】沿用 V1 檔頭的轉譯規則：ENUM→VARCHAR+CHECK、DATETIME→TIMESTAMPTZ、
-- JSON→JSONB、TINYINT→SMALLINT、CHAR(n)→VARCHAR(n)。
-- 刻意不引入 PostgreSQL 原生 enum type：新增值要 ALTER TYPE、且無法在交易中
-- 刪值，比 CHECK 難維護。此為實作等價，不算違反「與規格書一模一樣」。
--
-- 【索引名稱】規格書為部分表指定了索引名。定義相同僅名稱不同者一律 RENAME；
-- 定義本身要改的則重建。既有但規格書未提的索引一律保留（刪索引只會讓別人的
-- 查詢默默變慢、不會報錯，是最難追查的一類問題，沿用 V13 的處置）。


-- ===================================================================
-- 第一部分：刪除規格書明文廢除的兩張表
-- ===================================================================
-- 兩張都只有外向 FK，沒有任何表引用它們（audit.md §4.6.1 實查），
-- 所以不需要 CASCADE。用 IF EXISTS 讓本檔在已清過的庫上可重跑。

-- §7.5 L3193：資料遷移至 heat_reading 後 drop。
-- 職責由 heat_reading（各來源原始值）與 heat_composite_daily（合成值）取代。
DROP TABLE IF EXISTS trend_daily;

-- §7.2.8 L3007：v2.0 的 decision_feedback 已廢除，功能完全由 campaign_result 取代。
DROP TABLE IF EXISTS decision_feedback;


-- ===================================================================
-- 第二部分：逐表欄位對齊
-- ===================================================================

-- -------------------------------------------------------------------
-- §7.2.1 權限與稽核
-- -------------------------------------------------------------------

-- audit_log.entity_type：VARCHAR(64) → VARCHAR(48)（§7.2.1 L2548）
-- 現有資料最長 13 字元，縮短不會截斷。
ALTER TABLE audit_log
    ALTER COLUMN entity_type TYPE VARCHAR(48);

-- 規格書索引名為 idx_audit_time，本庫叫 idx_audit_created，定義相同。
ALTER INDEX IF EXISTS idx_audit_created RENAME TO idx_audit_time;


-- -------------------------------------------------------------------
-- §7.2.2 主檔
-- -------------------------------------------------------------------

-- category_lead_time 缺稽核欄位（§7.2.2 L2573）。
-- 這份前置期同時供 FR-17-1 節慶時間窗與 FR-16 時效落差使用，
-- 被誰改過、何時改的必須留痕。
ALTER TABLE category_lead_time
    ADD COLUMN IF NOT EXISTS updated_by BIGINT REFERENCES app_user (id),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

COMMENT ON COLUMN category_lead_time.updated_by IS '最後調整前置期的使用者（§FR-16-1）';

-- supplier.note：VARCHAR(500) → VARCHAR(255)（§7.2.2 L2612）。現有最長 17 字元。
ALTER TABLE supplier
    ALTER COLUMN note TYPE VARCHAR(255);

-- product：移除 target_audience、新增軟刪除兩欄
--
-- target_audience（v2.0 的逗號分隔客群代碼）§7.2.2 L2643 明訂已移除。
-- 客群資訊改由 category_audience_mix 於品類層級維護（本檔第三部分建立）。
ALTER TABLE product
    DROP COLUMN IF EXISTS target_audience;

-- 軟刪除（§7.2.2 L2637、§7.3）：非 NULL 者不出現在任何清單、排行、
-- 評分批次與報表。應用層以 Hibernate @SQLRestriction 統一套用。
ALTER TABLE product
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deleted_by BIGINT REFERENCES app_user (id);

COMMENT ON COLUMN product.deleted_at IS
    '軟刪除時間（§7.2.2）。非 NULL 者不出現在任何清單、排行、評分批次與報表；'
    '所有品項查詢一律帶 deleted_at IS NULL（§7.3）';

-- 規格書索引：idx_product_cat_status(category_id, status, deleted_at)。
-- 新索引是舊索引的前綴超集，舊的可以安全移除。
CREATE INDEX IF NOT EXISTS idx_product_cat_status
    ON product (category_id, status, deleted_at);
DROP INDEX IF EXISTS idx_product_category_status;

-- 定義相同，只是名稱與規格書不一致。
ALTER INDEX IF EXISTS idx_product_track_sourcing RENAME TO idx_product_track;

-- product_image.file_path：VARCHAR(500) → VARCHAR(255)（§7.2.2 L2647）。現有最長 32 字元。
ALTER TABLE product_image
    ALTER COLUMN file_path TYPE VARCHAR(255);

-- trend_keyword.lifecycle §7.2.2 L2659 明訂已移除（v3.0）。
-- 五值列舉與 sourcing_candidate.heat_stage 的三值、FR-06 內文的三段中文
-- 並存形成三套詞彙；v3.0 統一為三值，改由 heat_composite_daily.stage 逐日提供。
-- 內嵌的 trend_keyword_lifecycle_check 會隨欄位一起消失，不必另外 DROP。
ALTER TABLE trend_keyword
    DROP COLUMN IF EXISTS lifecycle;


-- -------------------------------------------------------------------
-- §7.2.3 熱度
-- -------------------------------------------------------------------

-- heat_source 缺三個欄位（§7.2.3 L2674、L2677、L2679）
ALTER TABLE heat_source
    ADD COLUMN IF NOT EXISTS granularity VARCHAR(16) NOT NULL DEFAULT 'KEYWORD',
    ADD COLUMN IF NOT EXISTS consecutive_probe_failures SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_probed_at TIMESTAMPTZ;

ALTER TABLE heat_source
    DROP CONSTRAINT IF EXISTS ck_heat_source_granularity;
ALTER TABLE heat_source
    ADD CONSTRAINT ck_heat_source_granularity
        CHECK (granularity IN ('KEYWORD', 'CATEGORY'));

ALTER TABLE heat_source
    DROP CONSTRAINT IF EXISTS ck_heat_source_probe_failures;
ALTER TABLE heat_source
    ADD CONSTRAINT ck_heat_source_probe_failures
        CHECK (consecutive_probe_failures >= 0);

COMMENT ON COLUMN heat_source.granularity IS
    '品類級來源於合成時套用 0.5 粒度折扣（§5.3.2）';
COMMENT ON COLUMN heat_source.consecutive_probe_failures IS
    '連續探測失敗次數，達 2 次轉 UNAVAILABLE（§7.2.3）';

-- §FR-06 明訂 Instagram「僅做品類級」，這是規格書指定的粒度，不是推測。
UPDATE heat_source SET granularity = 'CATEGORY'
WHERE source_code = 'INSTAGRAM' AND granularity <> 'CATEGORY';

-- heat_reading 增加品類維度（§7.2.3 L2691）
-- v2.0 只有 keyword_id 維度，Instagram 的品類級讀值無處落地。
ALTER TABLE heat_reading
    ADD COLUMN IF NOT EXISTS category_id BIGINT REFERENCES category (id) ON DELETE CASCADE;

-- 兩個維度擇一，所以 keyword_id 必須放寬為可 NULL。
ALTER TABLE heat_reading
    ALTER COLUMN keyword_id DROP NOT NULL;

ALTER TABLE heat_reading
    DROP CONSTRAINT IF EXISTS ck_heat_reading_target;
ALTER TABLE heat_reading
    ADD CONSTRAINT ck_heat_reading_target
        CHECK (keyword_id IS NOT NULL OR category_id IS NOT NULL);

-- 唯一鍵改為四欄（§7.2.3 L2697：uk_reading）。
-- 註：PostgreSQL 的 UNIQUE 預設把 NULL 視為互異，所以品類級讀值
-- （keyword_id 為 NULL）之間不會互相碰撞，符合本表「兩維度擇一」的設計。
ALTER TABLE heat_reading
    DROP CONSTRAINT IF EXISTS uk_heat_reading;
ALTER TABLE heat_reading
    DROP CONSTRAINT IF EXISTS uk_reading;
ALTER TABLE heat_reading
    ADD CONSTRAINT uk_reading UNIQUE (source_id, keyword_id, category_id, reading_date);


-- -------------------------------------------------------------------
-- §7.2.4 評論
-- -------------------------------------------------------------------

-- review_analysis.aspects §7.2.4 L2772 明訂改為結構化的 risk_topic，
-- 該欄已由 V14 建立並回填（V906），此處只剩把舊欄位移除。
ALTER TABLE review_analysis
    DROP COLUMN IF EXISTS aspects;

-- product_review.created_at 規格書欄位表未列，依 audit.md §4.5 裁決保留。


-- -------------------------------------------------------------------
-- §7.2.5 評分規則
-- -------------------------------------------------------------------

-- --- weight_version ---
--
-- 步驟 1：先拆掉會擋路的約束與索引。
-- ck_weight_version_threshold 綁在即將刪除的兩個門檻欄上；
-- ck_weight_version_active 與 uk_weight_version_active 的判準寫死 'ACTIVE' 字面值，
-- 不先拆掉，下面的 UPDATE 會被自己擋下。
ALTER TABLE weight_version
    DROP CONSTRAINT IF EXISTS ck_weight_version_threshold,
    DROP CONSTRAINT IF EXISTS ck_weight_version_active,
    DROP CONSTRAINT IF EXISTS weight_version_status_check,
    DROP CONSTRAINT IF EXISTS ck_weight_version_status;
DROP INDEX IF EXISTS uk_weight_version_active;

-- 步驟 2：刪除 v3.0 §7.2.5 表已無的純量門檻欄。
-- 門檻改由 grade_threshold 表逐榜保存（V13 已建立並回填）。
-- V13 的註解就已標明「勿再讀取」，此處完成清理。
ALTER TABLE weight_version
    DROP COLUMN IF EXISTS grade_a_threshold,
    DROP COLUMN IF EXISTS grade_b_threshold;

-- category_override_json 規格書欄位表未列，依 §4.5 裁決保留。

-- 步驟 3：新增 v3.0 的兩個欄位。
ALTER TABLE weight_version
    ADD COLUMN IF NOT EXISTS is_current BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS source_calibration_id BIGINT REFERENCES calibration_report (id);

COMMENT ON COLUMN weight_version.is_current IS
    '同一時間僅一筆為 true；GET /weight-versions/active 依此查詢（§7.2.5）';
COMMENT ON COLUMN weight_version.source_calibration_id IS
    '若由 §FR-15 校準產生，指向 calibration_report';

-- 步驟 4：狀態值 ACTIVE → APPROVED（§7.2.5 L2785）。
UPDATE weight_version SET status = 'APPROVED' WHERE status = 'ACTIVE';

-- is_current 回填：只把「最新一筆 APPROVED」標為 true。
-- 舊模型用 partial unique index 保證同時只有一筆 ACTIVE，所以正常情況下
-- 這裡只會有一筆；仍寫成明確取最新一筆，避免在髒資料上一次標多筆而
-- 讓下面的 unique index 建不起來。
UPDATE weight_version SET is_current = true
WHERE id = (SELECT id FROM weight_version
            WHERE status = 'APPROVED'
            ORDER BY effective_from DESC NULLS LAST, id DESC
            LIMIT 1);

-- 步驟 5：以新值域重建約束。
ALTER TABLE weight_version
    ADD CONSTRAINT ck_weight_version_status
        CHECK (status IN ('DRAFT', 'APPROVED', 'RETIRED'));

-- 原 ck_weight_version_active 改名，因為判準的狀態值本身變了，
-- 名稱留著 active 只會讓下一個人以為還有 ACTIVE 這個狀態。
ALTER TABLE weight_version
    ADD CONSTRAINT ck_weight_version_approved
        CHECK (status <> 'APPROVED' OR (approved_by IS NOT NULL AND effective_from IS NOT NULL));

-- 生效版本的唯一性判準由 status 改為 is_current 旗標（§7.2.5 L2787）。
CREATE UNIQUE INDEX uk_weight_version_current
    ON weight_version (is_current) WHERE is_current;

-- --- weight_profile ---
--
-- 因子代碼改名（§7.2.5 L2801）：HEAT_SLOPE→TREND、CONVERSION→CVR。
-- HEAT_VOLUME 不在 v3.0 的六個加分因子內（§5.2.1-a 已降為門檻條件，不進權重）。
--
-- 這裡刻意不「順手刪掉」HEAT_VOLUME 的列：每一組 (version_id, scene_type)
-- 的權重加總必須為 1.000（AC-08-1），刪一列會讓那一組加總變成小於 1，
-- 而且不會有任何錯誤訊息。真有這種列時直接讓 migration 失敗，由人決定
-- 權重要怎麼重新分配。共用庫實查為 0 列。
DO $$
DECLARE
    n INTEGER;
BEGIN
    SELECT count(*) INTO n FROM weight_profile WHERE factor_code = 'HEAT_VOLUME';
    IF n > 0 THEN
        RAISE EXCEPTION 'weight_profile 尚有 % 列 HEAT_VOLUME。v3.0 的六個加分因子不含它，'
                        '刪列會讓該組權重加總不足 1.000，必須先人工重新分配權重再套用 V17', n;
    END IF;
END $$;

ALTER TABLE weight_profile
    DROP CONSTRAINT IF EXISTS weight_profile_factor_code_check,
    DROP CONSTRAINT IF EXISTS ck_weight_profile_factor_code;

UPDATE weight_profile
SET factor_code = CASE factor_code
                      WHEN 'HEAT_SLOPE' THEN 'TREND'
                      WHEN 'CONVERSION' THEN 'CVR' END
WHERE factor_code IN ('HEAT_SLOPE', 'CONVERSION');

ALTER TABLE weight_profile
    ADD CONSTRAINT ck_weight_profile_factor_code
        CHECK (factor_code IN ('TREND', 'MARGIN', 'CVR', 'PRICE_FIT', 'FESTIVAL', 'CLIMATE'));


-- -------------------------------------------------------------------
-- §7.2.6 評分結果
-- -------------------------------------------------------------------

-- product_score：移除與 bonus_subtotal／penalty_subtotal 語意重複的兩欄。
-- §5.5 的公式只用 bonus_subtotal 與 penalty_subtotal，v3.0 §7.2.6 的欄位表
-- 也已無這兩欄。ck_score_formula 只引用保留下來的三欄，不受影響。
ALTER TABLE product_score
    DROP COLUMN IF EXISTS base_score,
    DROP COLUMN IF EXISTS risk_penalty;

-- period 已由 V13 改為 CHAR(7)，此處不需再動。

-- --- score_factor ---
--
-- 因子代碼與 weight_profile 同步改名。157 列資料必須先改，
-- 否則新的 CHECK 加不上去。
ALTER TABLE score_factor
    DROP CONSTRAINT IF EXISTS score_factor_factor_code_check,
    DROP CONSTRAINT IF EXISTS ck_score_factor_code;

UPDATE score_factor
SET factor_code = CASE factor_code
                      WHEN 'HEAT_SLOPE' THEN 'TREND'
                      WHEN 'CONVERSION' THEN 'CVR' END
WHERE factor_code IN ('HEAT_SLOPE', 'CONVERSION');

-- 同樣不默默刪列：score_factor 的每一列都是畫面上的一根長條，
-- 少一根不會報錯，只會讓因子透明化的畫面對不上。共用庫實查為 0 列。
DO $$
DECLARE
    n INTEGER;
BEGIN
    SELECT count(*) INTO n FROM score_factor WHERE factor_code = 'HEAT_VOLUME';
    IF n > 0 THEN
        RAISE EXCEPTION 'score_factor 尚有 % 列 HEAT_VOLUME，v3.0 的因子值域不含它，'
                        '必須先人工處理這些評分明細再套用 V17', n;
    END IF;
END $$;

-- §7.2.6 L2867 指定 VARCHAR(24)。現有最長 14 字元（LOGISTICS_RISK）。
ALTER TABLE score_factor
    ALTER COLUMN factor_code TYPE VARCHAR(24);

ALTER TABLE score_factor
    ADD CONSTRAINT ck_score_factor_code
        CHECK (factor_code IN (
            -- 六個加分因子
            'TREND', 'MARGIN', 'CVR', 'PRICE_FIT', 'FESTIVAL', 'CLIMATE',
            -- 三個扣分因子
            'REVIEW_RISK', 'LOGISTICS_RISK', 'INVENTORY_RISK'));

-- penalty_value：DECIMAL(5,2) → DECIMAL(4,1)（§7.2.6 L2871）。
-- 扣分上限 40（§FR-04），小數一位足夠；現有最大值 14.0。
ALTER TABLE score_factor
    ALTER COLUMN penalty_value TYPE DECIMAL(4, 1);

ALTER TABLE score_factor
    ADD COLUMN IF NOT EXISTS note VARCHAR(120);

COMMENT ON COLUMN score_factor.note IS
    '如「以全品類基準計算」「量級不足」「評論樣本不足」（§7.2.6）';

-- --- scene_classification_log ---
--
-- 三個欄位改名對齊規格書（§7.2.6 L2893、L2896）。
-- 既有的 CHECK 會跟著欄位改名自動更新引用，不必重建。
ALTER TABLE scene_classification_log
    RENAME COLUMN alternative_scene_type TO ai_alternative_scene;
ALTER TABLE scene_classification_log
    RENAME COLUMN signals TO ai_signals;

-- 規格書標明 ai_signals 為 JSON NULL：AI 判定失敗時整組 ai_* 欄位都沒有值，
-- 硬塞一個空陣列會讓「沒判定」與「判定結果是空的」分不出來。
ALTER TABLE scene_classification_log
    ALTER COLUMN ai_signals DROP NOT NULL,
    ALTER COLUMN ai_signals DROP DEFAULT;

-- period 是規格書有、本庫缺的欄位。覆寫率指標要按期別統計，沒有這欄
-- 就只能靠 created_at 去反推，跨週界時會算錯。
ALTER TABLE scene_classification_log
    ADD COLUMN IF NOT EXISTS period CHAR(7);

-- 回填：以 Asia/Taipei 判定 ISO 週（§7.2.6 period 定義）。
-- IYYY/IW 是 ISO 年與 ISO 週，跨年週不會被算到錯的年份。
UPDATE scene_classification_log
SET period = to_char(created_at AT TIME ZONE 'Asia/Taipei', 'IYYY"W"IW')
WHERE period IS NULL;

ALTER TABLE scene_classification_log
    ALTER COLUMN period SET NOT NULL;

ALTER TABLE scene_classification_log
    DROP CONSTRAINT IF EXISTS ck_scene_log_period_format;
ALTER TABLE scene_classification_log
    ADD CONSTRAINT ck_scene_log_period_format
        CHECK (period ~ '^[0-9]{4}W(0[1-9]|[1-4][0-9]|5[0-3])$');

-- fallback_applied／model／prompt_version／heat_bucket 規格書欄位表未列，
-- 依 §4.5 裁決保留。


-- -------------------------------------------------------------------
-- §7.2.7 AI
-- -------------------------------------------------------------------

-- ai_insight：規格書明訂 request_count 為主要度量、cost_usd 降為次要（§7.2.7 L2918）。
ALTER TABLE ai_insight
    ADD COLUMN IF NOT EXISTS model_alias   VARCHAR(32),
    ADD COLUMN IF NOT EXISTS request_count SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS from_cache    BOOLEAN  NOT NULL DEFAULT false;

ALTER TABLE ai_insight
    DROP CONSTRAINT IF EXISTS ck_ai_insight_request_count;
ALTER TABLE ai_insight
    ADD CONSTRAINT ck_ai_insight_request_count CHECK (request_count >= 0);

COMMENT ON COLUMN ai_insight.model_alias IS '邏輯別名，對應 §FR-07 的模型路由設定';
COMMENT ON COLUMN ai_insight.request_count IS
    '本次產出實際消耗的請求數（含重試與備援），為配額的主要度量；cost_usd 為次要（§FR-07）';
COMMENT ON COLUMN ai_insight.from_cache IS '命中快取時為 true，不計入配額';

-- ai_task：預算池與兩個計數欄
ALTER TABLE ai_task
    ADD COLUMN IF NOT EXISTS budget_pool     VARCHAR(16) NOT NULL DEFAULT 'TRACK_A',
    ADD COLUMN IF NOT EXISTS cache_hit_count INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS request_count   INTEGER     NOT NULL DEFAULT 0;

ALTER TABLE ai_task
    DROP CONSTRAINT IF EXISTS ck_ai_task_budget_pool;
ALTER TABLE ai_task
    ADD CONSTRAINT ck_ai_task_budget_pool
        CHECK (budget_pool IN ('TRACK_A', 'TRACK_B', 'RETRY'));

ALTER TABLE ai_task
    DROP CONSTRAINT IF EXISTS ck_ai_task_counters;
ALTER TABLE ai_task
    ADD CONSTRAINT ck_ai_task_counters
        CHECK (cache_hit_count >= 0 AND request_count >= 0);

-- 狀態值域：規格書為 SUCCEEDED，本庫是 SUCCESS（§7.2.7 L2931）。
ALTER TABLE ai_task
    DROP CONSTRAINT IF EXISTS ai_task_status_check,
    DROP CONSTRAINT IF EXISTS ck_ai_task_status;

UPDATE ai_task SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';

ALTER TABLE ai_task
    ADD CONSTRAINT ck_ai_task_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED'));

-- task_type：規格書 §7.2.7 L2929 列的四個值（FULL_ANALYSIS／SCENE_ONLY／
-- SCOUT／CALIBRATION）在本庫的 CHECK 裡一個都沒有，等於規格書指定的任務型別
-- 全都寫不進去。規格書該列以「…」結尾、值域是開放的，所以此處採「聯集」：
-- 補上規格書的四個值，同時保留 V7–V10 既有的七個值。
ALTER TABLE ai_task
    DROP CONSTRAINT IF EXISTS ai_task_task_type_check,
    DROP CONSTRAINT IF EXISTS ck_ai_task_type;
ALTER TABLE ai_task
    ADD CONSTRAINT ck_ai_task_type
        CHECK (task_type IN (
            -- §7.2.7 明列
            'FULL_ANALYSIS', 'SCENE_ONLY', 'SCOUT', 'CALIBRATION',
            -- V7–V10 既有，對應 §6.3 的各 Agent
            'SCENE_CLASSIFY', 'REVIEW_RISK', 'SELLING_POINT', 'RECOMMENDATION',
            'TREND_INTERPRET', 'SOURCING_SCOUT', 'WEIGHT_CALIBRATION'));

-- ai_task_item
--
-- raw_response §7.2.7 L2944 明訂已移除：LLM 原始回應可能含經模型改寫的
-- 評論片段，長期存 DB 會擴大機敏資料暴露面。除錯資訊改記應用日誌（§10）。
--
-- 註：ai_attempt 也有一個同名同義的 raw_response 欄位（V11 建立，規格書未提及）。
-- Vincent 2026-08-23 裁決不動它，該表由建立者負責（audit.md §4.6.0）。
-- 也就是說這條安全裁決目前只落實一半，這是已知且已被接受的狀態。
ALTER TABLE ai_task_item
    DROP COLUMN IF EXISTS raw_response;

-- 規格書 §7.2.7 L2939：product_id 與 keyword_id 依任務類型擇一。
-- 缺 keyword_id 時，TREND_INTERPRET／SOURCING_SCOUT 這類以關鍵字為標的的
-- 任務項目就沒有欄位可以指向標的。
ALTER TABLE ai_task_item
    ADD COLUMN IF NOT EXISTS keyword_id BIGINT REFERENCES trend_keyword (id) ON DELETE CASCADE;

-- 狀態值域對齊規格書（§7.2.7 L2940）。
-- SKIPPED_CACHE／SKIPPED_QUOTA 是規格書要求的兩個狀態，本庫完全沒有；
-- RUNNING／CANCELLED 則是本庫有而規格書沒有的兩個。此處嚴格照規格書，
-- 不保留 RUNNING／CANCELLED——任務層級的 ai_task.status 已有這兩個狀態。
ALTER TABLE ai_task_item
    DROP CONSTRAINT IF EXISTS ai_task_item_status_check,
    DROP CONSTRAINT IF EXISTS ck_ai_task_item_status;

UPDATE ai_task_item SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';
-- 執行中／已取消的項目在規格書的值域裡沒有對應值，一律回到 PENDING。
UPDATE ai_task_item SET status = 'PENDING' WHERE status IN ('RUNNING', 'CANCELLED');

ALTER TABLE ai_task_item
    ADD CONSTRAINT ck_ai_task_item_status
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'SKIPPED_CACHE', 'SKIPPED_QUOTA'));

-- attempt_count／next_retry_at／last_attempt_at 規格書欄位表未列，依 §4.5 裁決保留。


-- -------------------------------------------------------------------
-- §7.2.8 風險與決策
-- -------------------------------------------------------------------

-- risk_alert.risk_type：自由字串改為完整列舉（§7.2.8 L2954）。
--
-- 現有三個值不在 v3.0 的值域內，先改資料：
--   HEAT_PLUNGE      → HEAT_CRASH   （同一件事，v3.0 的命名）
--   TOTAL_PENALTY    → PENALTY_CAP  （同上）
--   SUPPLIER_ANOMALY → 刪列。§7.2.2 L2614 明訂「供應商異常」示警已移出本階段
--                      範圍（沒有任何資料源提供交期、到貨率或退件資訊），
--                      v3.0 的九個值裡沒有任何對應項，留著就是留一筆再也
--                      無法被重新產生、也無規則可解釋的示警。
ALTER TABLE risk_alert
    DROP CONSTRAINT IF EXISTS ck_risk_alert_type;

UPDATE risk_alert SET risk_type = 'HEAT_CRASH'  WHERE risk_type = 'HEAT_PLUNGE';
UPDATE risk_alert SET risk_type = 'PENALTY_CAP' WHERE risk_type = 'TOTAL_PENALTY';
DELETE FROM risk_alert WHERE risk_type = 'SUPPLIER_ANOMALY';

ALTER TABLE risk_alert
    ALTER COLUMN risk_type TYPE VARCHAR(32);

ALTER TABLE risk_alert
    ADD CONSTRAINT ck_risk_alert_type
        CHECK (risk_type IN (
            'REVIEW_RISK', 'LOGISTICS_RISK', 'INVENTORY_RISK', 'PENALTY_CAP',
            'HEAT_CRASH', 'HEAT_SURGE', 'SEASON_MISMATCH',
            'FESTIVAL_WINDOW_CLOSING', 'LOW_CONFIDENCE'));

-- §7.2.8 L2961：idx_alert_dedup 供 FR-10-2 的 7 日去重。
CREATE INDEX IF NOT EXISTS idx_alert_dedup
    ON risk_alert (product_id, risk_type, status, detected_at DESC);
-- 新索引以 product_id 開頭，完全涵蓋舊的單欄索引。
DROP INDEX IF EXISTS idx_risk_alert_product;

-- decision_record：五個新欄位（§7.2.8 L2974–L2981）
ALTER TABLE decision_record
    ADD COLUMN IF NOT EXISTS ai_action         VARCHAR(16),
    ADD COLUMN IF NOT EXISTS ai_qty_min        INTEGER,
    ADD COLUMN IF NOT EXISTS ai_qty_max        INTEGER,
    ADD COLUMN IF NOT EXISTS campaign_end_date DATE,
    ADD COLUMN IF NOT EXISTS reviewed_by       BIGINT REFERENCES app_user (id),
    ADD COLUMN IF NOT EXISTS reviewed_at       TIMESTAMPTZ;

ALTER TABLE decision_record
    DROP CONSTRAINT IF EXISTS ck_decision_ai_action;
ALTER TABLE decision_record
    ADD CONSTRAINT ck_decision_ai_action
        CHECK (ai_action IS NULL OR ai_action IN ('ADOPT', 'WATCH', 'REJECT'));

ALTER TABLE decision_record
    DROP CONSTRAINT IF EXISTS ck_decision_ai_qty_range;
ALTER TABLE decision_record
    ADD CONSTRAINT ck_decision_ai_qty_range
        CHECK (ai_qty_min IS NULL OR ai_qty_max IS NULL OR ai_qty_min <= ai_qty_max);

COMMENT ON COLUMN decision_record.campaign_end_date IS
    '結案日期，回填提醒的起算點（§FR-11-2）。v2.0 算不出「逾期天數」就是缺這一欄';

-- ai_action 回填：followed_ai 的定義就是 decision == ai_action（§7.2.8 L2975），
-- 所以 followed_ai = true 的列可以逆推出 ai_action，不是猜的。
-- followed_ai = false 的列只知道「AI 建議的不是這個」，無從得知是哪一個，留 NULL。
UPDATE decision_record SET ai_action = decision
WHERE followed_ai = true AND ai_action IS NULL;

-- §7.2.8 L2984：idx_decision_pending 供待回填查詢，避免全表掃描。
CREATE INDEX IF NOT EXISTS idx_decision_pending
    ON decision_record (campaign_end_date, decision);

-- --- campaign_snapshot：結構性重寫 ---
--
-- v3.0 §7.2.8 L2992 明訂「只存無法從 product_score + score_factor 還原的資訊」，
-- 表精簡為六欄。分數、分級、加減分小計、因子值全部可由
-- decision_record.score_id 一路 join 回去取得，不再重複保存。
--
-- 順序很重要：applied_thresholds 的回填要用到即將被刪掉的
-- weight_version_id 與 scene_type，所以必須先回填再刪欄。
ALTER TABLE campaign_snapshot
    ADD COLUMN IF NOT EXISTS applied_composite_weights JSONB,
    ADD COLUMN IF NOT EXISTS applied_thresholds        JSONB;

-- 用當時綁定的權重版本與情境，從 grade_threshold 取回該榜的 A／B 門檻。
-- 這是還原，不是編造：門檻本來就隨 version_id 版本化（V13）。
UPDATE campaign_snapshot cs
SET applied_thresholds = jsonb_build_object(
        'sceneType', cs.scene_type,
        'gradeAMin', gt.grade_a_min,
        'gradeBMin', gt.grade_b_min)
FROM grade_threshold gt
WHERE gt.version_id = cs.weight_version_id
  AND gt.scene_type = cs.scene_type
  AND cs.applied_thresholds IS NULL;

-- applied_composite_weights 刻意留 NULL：決策當下各熱度來源實際採用的
-- 合成權重從來沒有被記錄過，任何回填都是拿「現在的設定」冒充「當時的設定」。
-- 兩欄都允許 NULL，正是為了讓「本來就沒記錄」看得出來。
COMMENT ON COLUMN campaign_snapshot.applied_composite_weights IS
    '決策當下實際採用的各來源合成權重（§7.2.8）。V17 之前的列為 NULL，表示當時未記錄';
COMMENT ON COLUMN campaign_snapshot.applied_thresholds IS
    '決策當下該榜的 A／B 門檻（§7.2.8）';

-- 先拆掉綁在待刪欄位上的 CHECK，再刪欄。
ALTER TABLE campaign_snapshot
    DROP CONSTRAINT IF EXISTS campaign_snapshot_grade_check,
    DROP CONSTRAINT IF EXISTS ck_campaign_snapshot_scene;

-- 主鍵改為 decision_id（1:1）。既有的 UNIQUE(decision_id) 先拆掉，
-- 否則會多留一個與主鍵重複的索引。
ALTER TABLE campaign_snapshot
    DROP CONSTRAINT IF EXISTS campaign_snapshot_decision_id_key;

-- 刪掉 id 這個代理鍵，主鍵約束會隨欄位一起消失。
ALTER TABLE campaign_snapshot
    DROP COLUMN IF EXISTS id,
    DROP COLUMN IF EXISTS score,
    DROP COLUMN IF EXISTS grade,
    DROP COLUMN IF EXISTS bonus_subtotal,
    DROP COLUMN IF EXISTS penalty_subtotal,
    DROP COLUMN IF EXISTS weight_version_id,
    DROP COLUMN IF EXISTS scene_type,
    DROP COLUMN IF EXISTS factor_values;

ALTER TABLE campaign_snapshot
    ADD CONSTRAINT campaign_snapshot_pkey PRIMARY KEY (decision_id);

-- --- campaign_result ---
--
-- 精度與命名修正（§7.2.8 L3020）：所有比率欄位統一為 DECIMAL(5,4)，
-- realized_margin 更名為 realized_margin_rate 以消除「金額或率」的歧義。
--
-- 既有資料是百分比數（如 1.20 表示 1.2%、50.10 表示 50.1%），
-- 新值域是 0–1 的比率，所以要先除以 100 再改型別——
-- 順序反過來的話 50.10 會直接超出 DECIMAL(5,4) 而失敗。
ALTER TABLE campaign_result
    DROP CONSTRAINT IF EXISTS campaign_result_return_rate_check,
    DROP CONSTRAINT IF EXISTS ck_campaign_result_rates;

UPDATE campaign_result
SET return_rate     = return_rate / 100,
    realized_margin = realized_margin / 100
WHERE return_rate > 1 OR realized_margin > 1;

ALTER TABLE campaign_result
    ALTER COLUMN return_rate TYPE DECIMAL(5, 4);

ALTER TABLE campaign_result
    RENAME COLUMN realized_margin TO realized_margin_rate;
ALTER TABLE campaign_result
    ALTER COLUMN realized_margin_rate TYPE DECIMAL(5, 4);

ALTER TABLE campaign_result
    ADD CONSTRAINT ck_campaign_result_rates
        CHECK ((return_rate IS NULL OR (return_rate >= 0 AND return_rate <= 1))
            AND (realized_margin_rate IS NULL
                 OR (realized_margin_rate >= 0 AND realized_margin_rate <= 1)));

-- post_note_code 值域對齊（§7.2.8 L3016）：HEAT_FADED 是本庫的舊名，
-- 規格書為 HEAT_PASSED；OTHER 是規格書有而本庫缺的值。
ALTER TABLE campaign_result
    DROP CONSTRAINT IF EXISTS campaign_result_post_note_code_check,
    DROP CONSTRAINT IF EXISTS ck_campaign_result_post_note;

UPDATE campaign_result SET post_note_code = 'HEAT_PASSED' WHERE post_note_code = 'HEAT_FADED';

ALTER TABLE campaign_result
    ADD CONSTRAINT ck_campaign_result_post_note
        CHECK (post_note_code IS NULL OR post_note_code IN (
            'FASTER_THAN_EXPECTED', 'HEAT_PASSED', 'QUALITY_ISSUE', 'LOGISTICS_ISSUE', 'OTHER'));

-- 主鍵改為 decision_id（1:1，§7.2.8 L3011）。
ALTER TABLE campaign_result
    DROP CONSTRAINT IF EXISTS campaign_result_decision_id_key;
ALTER TABLE campaign_result
    DROP COLUMN IF EXISTS id;
ALTER TABLE campaign_result
    ADD CONSTRAINT campaign_result_pkey PRIMARY KEY (decision_id);


-- -------------------------------------------------------------------
-- §7.2.9 B 軌
-- -------------------------------------------------------------------

-- sourcing_candidate：主關聯由關鍵字改為品項（§7.2.9 L3029）
--
-- v2.0 綁 keyword_id 與 product.track_type 的模型互斥，導致 AC-16-5
-- 「成案轉軌後熱度資料完整保留」沒有實作路徑。改綁品項後，轉軌只需改
-- track_type，熱度、標記與關鍵字關聯全部自然保留。
--
-- keyword_id 保留為「當初從哪個關鍵字挖出來」的來源紀錄（Vincent 2026-08-23
-- 裁決，audit.md §4.6.0），但必須配三項處置，缺一不可：
--   1. FK 由 ON DELETE CASCADE 改為 ON DELETE SET NULL
--   2. 放寬為可 NULL
--   3. 加 COMMENT 標明它是歷史來源，不是即時關聯
ALTER TABLE sourcing_candidate
    ADD COLUMN IF NOT EXISTS product_id              BIGINT REFERENCES product (id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS lead_time_overridden_by BIGINT REFERENCES app_user (id),
    ADD COLUMN IF NOT EXISTS scouted_at              TIMESTAMPTZ;

-- 回填 product_id：經 product_keyword 找該關鍵字對應的 B 軌品項。
-- 共用庫實查四列各自只對到唯一一個 B 軌品項，所以這個對映是確定的；
-- 對不到或對到多個時 product_id 會留 NULL，由下面的檢查擋下。
UPDATE sourcing_candidate sc
SET product_id = (SELECT p.id
                  FROM product_keyword pk
                           JOIN product p ON p.id = pk.product_id
                  WHERE pk.keyword_id = sc.keyword_id
                    AND p.track_type = 'B')
WHERE sc.product_id IS NULL
  AND sc.keyword_id IS NOT NULL
  AND (SELECT count(*)
       FROM product_keyword pk
                JOIN product p ON p.id = pk.product_id
       WHERE pk.keyword_id = sc.keyword_id
         AND p.track_type = 'B') = 1;

-- product_id 是本表的主關聯，回填不完整就直接停下，不留半套資料。
DO $$
DECLARE
    n INTEGER;
BEGIN
    SELECT count(*) INTO n FROM sourcing_candidate WHERE product_id IS NULL;
    IF n > 0 THEN
        RAISE EXCEPTION 'sourcing_candidate 有 % 列無法自動對映到唯一的 B 軌品項。'
                        '請先以 product_keyword 補齊關聯或清掉這些列，再套用 V17', n;
    END IF;
END $$;

ALTER TABLE sourcing_candidate
    ALTER COLUMN product_id SET NOT NULL;

-- §7.2.9 L3029：product_id 為 UNIQUE，一個品項只會有一列候選紀錄。
ALTER TABLE sourcing_candidate
    DROP CONSTRAINT IF EXISTS uk_sourcing_product;
ALTER TABLE sourcing_candidate
    ADD CONSTRAINT uk_sourcing_product UNIQUE (product_id);

-- keyword_id 的三項配套。
-- 原 FK 是 ON DELETE CASCADE：刪一個 trend_keyword 會連帶刪掉整列候選。
-- 改綁品項後這列的擁有者是品項，卻仍掛著由關鍵字觸發的刪除，
-- 而且 CASCADE 不報錯不留痕——這是本次改動裡最危險的一條靜默路徑。
DO $$
DECLARE
    fk_name TEXT;
BEGIN
    SELECT con.conname INTO fk_name
    FROM pg_constraint con
             JOIN pg_class rel ON rel.oid = con.conrelid
    WHERE rel.relname = 'sourcing_candidate'
      AND con.contype = 'f'
      AND pg_get_constraintdef(con.oid) LIKE '%REFERENCES trend_keyword%';
    IF fk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE sourcing_candidate DROP CONSTRAINT %I', fk_name);
    END IF;
END $$;

ALTER TABLE sourcing_candidate
    ALTER COLUMN keyword_id DROP NOT NULL;

ALTER TABLE sourcing_candidate
    ADD CONSTRAINT fk_sourcing_keyword
        FOREIGN KEY (keyword_id) REFERENCES trend_keyword (id) ON DELETE SET NULL;

COMMENT ON COLUMN sourcing_candidate.keyword_id IS
    '【來源紀錄，非即時關聯】當初是從哪個關鍵字挖出這個候選的。'
    '本表的主關聯是 product_id（§7.2.9 v3.0 裁決）。'
    '本欄允許與 product_keyword 的現況不一致，不要拿它做即時 join';

-- status §7.2.9 L3043 明訂不重複於本表，一律以 product.sourcing_status 為準。
-- ck_sourcing_reject 引用了 status，必須先拆。
ALTER TABLE sourcing_candidate
    DROP CONSTRAINT IF EXISTS ck_sourcing_reject;
ALTER TABLE sourcing_candidate
    DROP CONSTRAINT IF EXISTS sourcing_candidate_status_check;
ALTER TABLE sourcing_candidate
    DROP COLUMN IF EXISTS status;

-- category_id 規格書欄位表未列，且品類可由 product.category_id 取得，
-- 屬於「同一件事存兩份」。但依 §4.5 裁決「欄位表未列的一律保留」，此處不刪。

-- 規格書標為可 NULL 的三欄放寬（§7.2.9 L3030–L3035）。
-- 候選剛建立時 SourcingScoutAgent 還沒跑，這三個值本來就還不存在；
-- NOT NULL 會逼人先填一個假的 0 進去。
ALTER TABLE sourcing_candidate
    ALTER COLUMN heat_stage DROP NOT NULL,
    ALTER COLUMN estimated_lifespan_days DROP NOT NULL,
    ALTER COLUMN time_gap_days DROP NOT NULL;

-- §7.2.9 L3039：idx_sourcing_gap(time_gap_days ASC) 供 FR-16-2 的升冪排序。
-- 舊索引以已刪除的 status 開頭，時效落差排序根本用不到它。
DROP INDEX IF EXISTS idx_sourcing_gap;
CREATE INDEX idx_sourcing_gap ON sourcing_candidate (time_gap_days ASC);


-- -------------------------------------------------------------------
-- §7.2.10 節慶與氣候
-- -------------------------------------------------------------------

-- festival_code 兩處都改為 VARCHAR(24)（§7.2.10 L3052、L3065）。現有最長 14 字元。
ALTER TABLE festival_calendar
    ALTER COLUMN festival_code TYPE VARCHAR(24);
ALTER TABLE item_festival_affinity
    ALTER COLUMN festival_code TYPE VARCHAR(24);

-- year：INTEGER → SMALLINT（§7.2.10 L3055）。
-- ck_festival_year 用 EXTRACT 比對，型別縮小不影響。
ALTER TABLE festival_calendar
    ALTER COLUMN year TYPE SMALLINT;

-- item_festival_affinity：AI 建議者需人工確認後才生效（§7.2.10 L3067）。
-- DEFAULT 'MANUAL' 是刻意的：既有資料都是 V900 手動種下的。
ALTER TABLE item_festival_affinity
    ADD COLUMN IF NOT EXISTS set_by       VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS confirmed_by BIGINT REFERENCES app_user (id),
    ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ;

ALTER TABLE item_festival_affinity
    DROP CONSTRAINT IF EXISTS ck_affinity_set_by;
ALTER TABLE item_festival_affinity
    ADD CONSTRAINT ck_affinity_set_by
        CHECK (set_by IN ('MANUAL', 'AI_SUGGESTED'));

COMMENT ON COLUMN item_festival_affinity.set_by IS
    'AI_SUGGESTED 者需人工確認（填 confirmed_by／confirmed_at）後才生效（§FR-17-1）';


-- -------------------------------------------------------------------
-- §7.2.11 校準與批次
-- -------------------------------------------------------------------

-- calibration_report.accepted_items（§7.2.11 L3094）：部分採納時逐項勾選的結果。
--
-- 註：本庫另有一個 accepted_adjustments 欄位（V10 建立，規格書未列），
-- 語意疑似相同。依 §4.5 裁決保留未列欄位，所以兩欄並存。
-- 要合併成一欄的話屬於 V10 作者的範圍，不在本檔處理。
ALTER TABLE calibration_report
    ADD COLUMN IF NOT EXISTS accepted_items JSONB;

ALTER TABLE calibration_report
    DROP CONSTRAINT IF EXISTS ck_calibration_accepted_items_array;
ALTER TABLE calibration_report
    ADD CONSTRAINT ck_calibration_accepted_items_array
        CHECK (accepted_items IS NULL OR jsonb_typeof(accepted_items) = 'array');

-- sales_record.audience_tag → audience_code VARCHAR(24)（§7.2.11 L3109）
--
-- 新欄位對應 audience_segment.audience_code（單一代碼），
-- 舊欄位放的是逗號分隔的人口統計標籤（如 'F18_24,F25_34'）。
-- 逗號串在新語意下無法解讀，也 join 不到任何客群，一律清成 NULL；
-- 正確的客群代碼由重寫後的假資料 V900–V906 供給。
ALTER TABLE sales_record
    RENAME COLUMN audience_tag TO audience_code;
ALTER TABLE sales_record
    ALTER COLUMN audience_code TYPE VARCHAR(24);

UPDATE sales_record SET audience_code = NULL WHERE audience_code LIKE '%,%';

COMMENT ON COLUMN sales_record.audience_code IS
    '對應 audience_segment.audience_code（§7.2.11）。供 PRICE_FIT 因子計算';

-- import_batch 缺三個欄位（§7.2.11 L3116）
ALTER TABLE import_batch
    ADD COLUMN IF NOT EXISTS file_size   BIGINT,
    ADD COLUMN IF NOT EXISTS is_async    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ;

-- data_type 值域對齊（§7.2.11 L3120：SALES / REVIEW / AUDIENCE / PRODUCT）。
ALTER TABLE import_batch
    DROP CONSTRAINT IF EXISTS import_batch_data_type_check,
    DROP CONSTRAINT IF EXISTS ck_import_batch_data_type;

UPDATE import_batch
SET data_type = CASE data_type
                    WHEN 'SALES_RECORD'   THEN 'SALES'
                    WHEN 'PRODUCT_REVIEW' THEN 'REVIEW'
                    WHEN 'MEMBER_PROFILE' THEN 'AUDIENCE'
                    WHEN 'PRODUCT_MASTER' THEN 'PRODUCT' END
WHERE data_type IN ('SALES_RECORD', 'PRODUCT_REVIEW', 'MEMBER_PROFILE', 'PRODUCT_MASTER');

ALTER TABLE import_batch
    ADD CONSTRAINT ck_import_batch_data_type
        CHECK (data_type IN ('SALES', 'REVIEW', 'AUDIENCE', 'PRODUCT'));


-- ===================================================================
-- 第三部分：建立規格書有、本庫缺的六張表
-- ===================================================================

-- --- §7.2.1 refresh_token ---
-- 支援 FR-01 的 token 換發與撤銷。
CREATE TABLE refresh_token
(
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- SHA-256 的十六進位表示恰為 64 字元。不存明文：資料庫外流時
    -- 明文 token 等同於一組可直接使用的登入憑證。
    token_hash VARCHAR(64)  NOT NULL UNIQUE,
    issued_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    user_agent VARCHAR(255),
    ip         VARCHAR(45),

    CONSTRAINT ck_refresh_token_window CHECK (expires_at > issued_at)
);

COMMENT ON TABLE refresh_token IS 'FR-01 的 refresh token 換發與撤銷紀錄（§7.2.1）';
COMMENT ON COLUMN refresh_token.token_hash IS 'SHA-256 十六進位，不存明文';
COMMENT ON COLUMN refresh_token.revoked_at IS '登出或強制撤銷時寫入；非 NULL 即失效';

-- 換發時要用 user_id 查該使用者所有未過期的 token。
CREATE INDEX idx_refresh_token_user ON refresh_token (user_id, expires_at DESC);

-- --- §7.2.2 audience_segment ---
-- 去識別化的客群統計，不含任何個人資料。供 PRICE_FIT 因子計算（§5.2.4）。
CREATE TABLE audience_segment
(
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    audience_code VARCHAR(24)    NOT NULL UNIQUE,
    name          VARCHAR(50)    NOT NULL,
    price_min     DECIMAL(10, 2) NOT NULL,
    price_max     DECIMAL(10, 2) NOT NULL,
    note          VARCHAR(255),

    CONSTRAINT ck_audience_price_order CHECK (price_min <= price_max),
    CONSTRAINT ck_audience_price_range CHECK (price_min >= 0)
);

COMMENT ON TABLE audience_segment IS
    '去識別化的客群統計，不含任何個人資料（§7.2.2）。'
    'v2.0 的 PRICE_FIT 因子標明資料來源為「會員輪廓」卻沒有對應的表，因子無法計算';
COMMENT ON COLUMN audience_segment.audience_code IS '如 MAIN、PRICE_SENSITIVE、PREMIUM';

-- --- §7.2.2 category_audience_mix ---
CREATE TABLE category_audience_mix
(
    category_id BIGINT        NOT NULL REFERENCES category (id) ON DELETE CASCADE,
    audience_id BIGINT        NOT NULL REFERENCES audience_segment (id) ON DELETE CASCADE,
    share       DECIMAL(4, 3) NOT NULL,

    CONSTRAINT pk_category_audience_mix PRIMARY KEY (category_id, audience_id),
    CONSTRAINT ck_audience_mix_share CHECK (share >= 0 AND share <= 1)
);

COMMENT ON TABLE category_audience_mix IS
    '品類的客群組成（§7.2.2）。同一 category 的 share 加總須為 1.000，由應用層驗證——'
    '單列 CHECK 看不到同組其他列';

-- --- §7.2.3 heat_composite_daily ---
-- 多來源合成後的每日熱度，是 FR-06 曲線、§5.3.3 斜率與 §5.8 階段判定的唯一資料來源。
CREATE TABLE heat_composite_daily
(
    keyword_id               BIGINT        NOT NULL REFERENCES trend_keyword (id) ON DELETE CASCADE,
    stat_date                DATE          NOT NULL,
    composite_value          DECIMAL(6, 2) NOT NULL,
    -- 斜率是比率，可為負；雙窗口用來判斷背離
    slope_7d                 DECIMAL(8, 4),
    slope_30d                DECIMAL(8, 4),
    stage                    VARCHAR(16)   NOT NULL,
    stage_weeks              SMALLINT      NOT NULL DEFAULT 0,
    estimated_lifespan_days  INTEGER,
    -- 本次實際採用的各來源合成權重，供事後追溯（§FR-06 顯示需求）
    applied_weights          JSONB         NOT NULL,
    divergence_flag          BOOLEAN       NOT NULL DEFAULT false,
    volume_below_floor       BOOLEAN       NOT NULL DEFAULT false,

    CONSTRAINT pk_heat_composite_daily PRIMARY KEY (keyword_id, stat_date),
    CONSTRAINT ck_heat_composite_value CHECK (composite_value >= 0 AND composite_value <= 100),
    CONSTRAINT ck_heat_composite_stage CHECK (stage IN ('RISING', 'PLATEAU', 'DECLINING')),
    CONSTRAINT ck_heat_composite_stage_weeks CHECK (stage_weeks >= 0),
    CONSTRAINT ck_heat_composite_lifespan CHECK (estimated_lifespan_days IS NULL OR estimated_lifespan_days >= 0),
    CONSTRAINT ck_heat_composite_weights CHECK (jsonb_typeof(applied_weights) = 'object')
);

COMMENT ON TABLE heat_composite_daily IS
    '多來源合成後的每日熱度（§7.2.3，v3.0 新增）。FR-06 曲線、§5.3.3 斜率、'
    '§5.8 階段判定的唯一資料來源。v1.0 的 trend_daily 於 v3.0 廢除，職責由'
    'heat_reading（各來源原始值）與本表（合成值）分擔';
COMMENT ON COLUMN heat_composite_daily.applied_weights IS
    '本次實際採用的各來源合成權重。來源不可用時權重會被重新分配，'
    '不記下來就無法解釋當天的合成值是怎麼算出來的';
COMMENT ON COLUMN heat_composite_daily.divergence_flag IS '7 日與 30 日斜率背離（可能見頂）';
COMMENT ON COLUMN heat_composite_daily.volume_below_floor IS '熱度量級未達下限（§5.2.1-a）';

-- 主鍵 (keyword_id, stat_date) 即為 90 日區間查詢的索引（§7.3），不另建索引。

-- --- §7.2.5 risk_rule ---
-- 三類扣分規則與 FR-10-1 各示警類型的門檻，由 SYS_ADMIN 維護。
CREATE TABLE risk_rule
(
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    rule_code      VARCHAR(32)   NOT NULL,
    -- 非 NULL 時為該品類的覆寫值；NULL 為全域預設
    category_id    BIGINT        REFERENCES category (id) ON DELETE CASCADE,
    threshold_json JSONB         NOT NULL,
    max_penalty    DECIMAL(4, 1),
    enabled        BOOLEAN       NOT NULL DEFAULT true,
    updated_by     BIGINT        REFERENCES app_user (id),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- 【與規格書字面的差異，刻意為之】
    -- §7.2.5 L2827 把 rule_code 標為 UNIQUE，但同一節的 category_id 又說
    -- 「非 NULL 時為該品類的覆寫值」。兩者不可能同時成立：rule_code 全域唯一
    -- 就只能存在一列，品類覆寫永遠寫不進去，category_id 這個欄位等於廢的。
    -- 此處取「唯一性是 (rule_code, category_id)」的解讀，讓覆寫機制成立。
    -- NULLS NOT DISTINCT 讓同一個 rule_code 的全域預設也只能有一列。
    CONSTRAINT uk_risk_rule UNIQUE NULLS NOT DISTINCT (rule_code, category_id),
    CONSTRAINT ck_risk_rule_threshold CHECK (jsonb_typeof(threshold_json) = 'object'),
    CONSTRAINT ck_risk_rule_max_penalty CHECK (max_penalty IS NULL OR max_penalty >= 0)
);

COMMENT ON TABLE risk_rule IS
    '三類扣分規則與 FR-10-1 各示警類型的門檻，由 SYS_ADMIN 維護（§7.2.5）';
COMMENT ON COLUMN risk_rule.rule_code IS
    'REVIEW_RISK／LOGISTICS_RISK／INVENTORY_RISK／HEAT_CRASH 等（§FR-10-1）';
COMMENT ON COLUMN risk_rule.threshold_json IS
    '各規則的門檻參數，如負評率 0.15、slope 門檻 −0.40';
COMMENT ON COLUMN risk_rule.enabled IS
    '三類扣分規則不可停用，由應用層強制（本欄不設 CHECK：規則代碼是可擴充的）';

CREATE INDEX idx_risk_rule_lookup ON risk_rule (rule_code, category_id) WHERE enabled;

-- --- §7.2.11 report_job ---
CREATE TABLE report_job
(
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    report_type  VARCHAR(24) NOT NULL,
    format       VARCHAR(8)  NOT NULL,
    params_json  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    file_path    VARCHAR(255),
    row_count    INTEGER,
    requested_by BIGINT      NOT NULL REFERENCES app_user (id),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,

    CONSTRAINT ck_report_job_type CHECK (report_type IN (
        'WEEKLY_PICK', 'SCORE_DETAIL', 'ACCURACY', 'SOURCING_QUEUE', 'CALIBRATION')),
    CONSTRAINT ck_report_job_format CHECK (format IN ('PDF', 'XLSX')),
    CONSTRAINT ck_report_job_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_report_job_params CHECK (jsonb_typeof(params_json) = 'object'),
    CONSTRAINT ck_report_job_row_count CHECK (row_count IS NULL OR row_count >= 0),
    -- 成功的任務一定要指得出檔案位置，否則下載端點無從實作
    CONSTRAINT ck_report_job_file CHECK (status <> 'SUCCEEDED' OR file_path IS NOT NULL)
);

COMMENT ON TABLE report_job IS
    'FR-12 五種報表的任務狀態與檔案位置（§7.2.11，v3.0 新增）。'
    'v2.0 有三個報表端點卻沒有任何資料表承載任務狀態';

CREATE INDEX idx_report_job_requester ON report_job (requested_by, requested_at DESC);


-- ===================================================================
-- 第四部分：新表的 RLS 與 ssds_app policy
-- ===================================================================
-- ALTER DEFAULT PRIVILEGES 管得到表層 GRANT，管不到 RLS，也管不到 policy。
-- 少了這一段，新表對 ssds_app 就是一張永遠空的表，而且不會有任何錯誤訊息。
-- 每一支建表的 migration 都必須自己補（V16 檔頭、V13 第四部分同一件事）。
-- ssds-infra 的 MigrationVerificationTest 會驗證這兩件事。

ALTER TABLE refresh_token          ENABLE ROW LEVEL SECURITY;
ALTER TABLE audience_segment       ENABLE ROW LEVEL SECURITY;
ALTER TABLE category_audience_mix  ENABLE ROW LEVEL SECURITY;
ALTER TABLE heat_composite_daily   ENABLE ROW LEVEL SECURITY;
ALTER TABLE risk_rule              ENABLE ROW LEVEL SECURITY;
ALTER TABLE report_job             ENABLE ROW LEVEL SECURITY;

-- 角色可能不存在（V16 之前的乾淨庫、或本機測試容器先跑到這裡），
-- 所以整段包在存在性判斷裡，與 V16 的姿態一致。
DO $$
DECLARE
    tbl TEXT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ssds_app') THEN
        RAISE NOTICE 'V17：角色 ssds_app 不存在，略過 GRANT 與 policy';
        RETURN;
    END IF;

    FOREACH tbl IN ARRAY ARRAY['refresh_token', 'audience_segment', 'category_audience_mix',
                               'heat_composite_daily', 'risk_rule', 'report_job']
        LOOP
            EXECUTE format(
                    'GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO ssds_app', tbl);
            IF NOT EXISTS (SELECT 1
                           FROM pg_policy pol
                                    JOIN pg_class c ON c.oid = pol.polrelid
                           WHERE c.relname = tbl
                             AND pol.polname = 'p_ssds_app_rw') THEN
                EXECUTE format(
                        'CREATE POLICY p_ssds_app_rw ON public.%I FOR ALL TO ssds_app '
                        'USING (true) WITH CHECK (true)', tbl);
            END IF;
        END LOOP;
END $$;
