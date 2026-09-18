-- Phase 2：補齊 v3.0.1 因子來源追溯與可執行的扣分規則契約。

-- §7.2.6 / E-02：多關鍵字、多節慶取最大值後，正式快照必須保存生效標的。
ALTER TABLE score_factor
    ADD COLUMN driving_keyword_id BIGINT REFERENCES trend_keyword (id),
    ADD COLUMN driving_festival_id BIGINT REFERENCES festival_calendar (id),
    ADD CONSTRAINT ck_score_factor_driving_keyword
        CHECK (driving_keyword_id IS NULL OR factor_code = 'TREND'),
    ADD CONSTRAINT ck_score_factor_driving_festival
        CHECK (driving_festival_id IS NULL OR factor_code = 'FESTIVAL');

CREATE INDEX idx_score_factor_driving_keyword
    ON score_factor (driving_keyword_id) WHERE driving_keyword_id IS NOT NULL;
CREATE INDEX idx_score_factor_driving_festival
    ON score_factor (driving_festival_id) WHERE driving_festival_id IS NOT NULL;

COMMENT ON COLUMN score_factor.driving_keyword_id IS
    'TREND 多關鍵字取最大值時本次生效的關鍵字（§5.3.3、v3.0.1）';
COMMENT ON COLUMN score_factor.driving_festival_id IS
    'FESTIVAL 多節慶取最大值時本次生效的節慶日（AC-17-6、v3.0.1）';

-- §5.2.2：正式程式只讀 risk_rule，不把可調門檻與點數寫死。
-- 正式環境若已有規則，增量補齊欄位並修正規格上限；乾淨環境無資料時不插入假資料。
UPDATE risk_rule
SET threshold_json =
        '{"negativeRateThreshold":0.15,"minSampleSize":20}'::jsonb || threshold_json,
    max_penalty = 20.0,
    updated_at = now()
WHERE rule_code = 'REVIEW_RISK';

UPDATE risk_rule
SET threshold_json =
        '{"meltableSummerPoints":4,"coldChainPoints":4,"fragilePoints":3,"oversizedPoints":3}'::jsonb
        || threshold_json,
    max_penalty = 10.0,
    updated_at = now()
WHERE rule_code = 'LOGISTICS_RISK';

UPDATE risk_rule
SET threshold_json =
        '{"shelfLifeDaysThreshold":60,"shortShelfLifePoints":4,"seasonalPoints":3,"moqThreshold":300,"highMoqPoints":3}'::jsonb
        || threshold_json,
    max_penalty = 10.0,
    updated_at = now()
WHERE rule_code = 'INVENTORY_RISK' AND category_id IS NULL;

-- 品類覆寫保留自己的門檻，只補逐條件點數與正確上限。
UPDATE risk_rule
SET threshold_json =
        '{"shortShelfLifePoints":4,"seasonalPoints":3,"highMoqPoints":3}'::jsonb
        || threshold_json,
    max_penalty = 10.0,
    updated_at = now()
WHERE rule_code = 'INVENTORY_RISK' AND category_id IS NOT NULL;
