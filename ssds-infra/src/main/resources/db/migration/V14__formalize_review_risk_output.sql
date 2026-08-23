-- Agent 2（ReviewRiskAgent）正式輸出欄位，對齊規格書 v3.0 §7.2.4。
--
-- 不修改任何既有 migration。aspects 暫不刪除，因為 dev profile 的既有 V903
-- 會在 V14 之後執行且仍引用該欄位；應用程式自 V14 起完全停止讀寫 aspects。
--
-- 【上線前必check】共用資料庫的 flyway_schema_history 必須有 version = '12'
-- 那一列。缺了的話 Flyway 會在 out-of-order 模式下重跑 V12，撞上
-- 「constraint ck_scene_heat_bucket already exists」而整批失敗。

ALTER TABLE review_analysis
    ADD COLUMN risk_topic VARCHAR(24),
    ADD COLUMN prompt_version VARCHAR(20) NOT NULL DEFAULT 'pre-v14';

-- 回填既有資料。
--
-- aspects 是逗號分隔的多值欄位（V903 會寫入 '風味,包裝' 這類值），
-- 等值比對只在剛好單值時才對得上，多值一律落到 OTHER。改用子字串比對。
--
-- WHEN 的順序就是風險優先序：一筆負評同時提到食安與品質時，記較嚴重的那個。
UPDATE review_analysis
SET risk_topic = CASE
    WHEN sentiment <> 'NEGATIVE'                    THEN NULL
    WHEN aspects IS NULL                            THEN 'OTHER'
    WHEN aspects ~ '(食安|FOOD_SAFETY)'             THEN 'FOOD_SAFETY'
    WHEN aspects ~ '(品質|QUALITY)'                 THEN 'QUALITY'
    WHEN aspects ~ '(物流破損|SHIPPING_DAMAGE)'     THEN 'SHIPPING_DAMAGE'
    WHEN aspects ~ '(價格|PRICE)'                   THEN 'PRICE'
    ELSE 'OTHER'
END;

-- 回填完才拿掉 DEFAULT。
--
-- 留著 DEFAULT 的話，日後應用程式若忘記帶 prompt_version，那筆會被靜默標成
-- 'pre-v14' —— 而這個欄位存在的唯一理由就是回溯「哪一版 prompt 產的」，
-- 標錯比寫不進去更難查。拿掉之後，沒帶值的 INSERT 會當場失敗。
--
-- 【這需要 Entity 配合】ReviewAnalysis 的寫入路徑必須顯式帶入 prompt_version，
-- 否則 V14 上線後所有 Agent 2 的寫入都會撞 NOT NULL。若還沒改好，
-- 就先把這一句註解掉，改好之後再開一支 migration 補。
ALTER TABLE review_analysis
    ALTER COLUMN prompt_version DROP DEFAULT;

ALTER TABLE review_analysis
    ADD CONSTRAINT ck_review_analysis_risk_topic
        CHECK (risk_topic IS NULL OR risk_topic IN (
            'QUALITY', 'FOOD_SAFETY', 'SHIPPING_DAMAGE', 'PRICE', 'OTHER')),
    ADD CONSTRAINT ck_review_analysis_risk_topic_only_negative
        CHECK (risk_topic IS NULL OR sentiment = 'NEGATIVE');

COMMENT ON COLUMN review_analysis.risk_topic IS
    'Agent 2 負評主題；僅 NEGATIVE 評論有值（規格書 v3.0 §7.2.4）';
COMMENT ON COLUMN review_analysis.prompt_version IS
    '產生分析結果的 Prompt 模板版本；V14 前的既有資料標記為 pre-v14。'
    'V14 起無預設值，寫入端必須顯式帶入';
COMMENT ON COLUMN review_analysis.aspects IS
    '已棄用；僅保留供既有 V903 dev seed 通過，應用程式不得讀寫';
