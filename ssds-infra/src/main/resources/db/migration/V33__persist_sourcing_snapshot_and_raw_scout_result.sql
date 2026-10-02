-- V32 曾重新加入候選剩餘壽命快照；規格 §7.2.9 明定唯一權威來源為
-- driving_keyword_id 對應的 heat_composite_daily 每日列，因此只向前移除。
-- V32 已套用且不得改寫，修正必須留在新的正式 migration。
ALTER TABLE sourcing_candidate
    DROP COLUMN IF EXISTS estimated_lifespan_days;

-- 不依賴 product_id 的 Agent 6 探索輸入／結果；探索本身不建立主資料。

ALTER TABLE ai_task_item
    ADD COLUMN scout_keyword VARCHAR(80),
    ADD COLUMN scout_category_id BIGINT REFERENCES category (id) ON DELETE RESTRICT,
    ADD COLUMN scout_report TEXT,
    ADD COLUMN scout_opportunity_signals JSONB,
    ADD COLUMN scout_risk_signals JSONB,
    ADD COLUMN scout_model VARCHAR(80),
    ADD COLUMN scout_prompt_version VARCHAR(20),
    ADD COLUMN scout_report_generated_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_ai_task_item_scout_input
        CHECK (scout_keyword IS NULL OR scout_category_id IS NOT NULL),
    ADD CONSTRAINT ck_ai_task_item_scout_opportunities_json
        CHECK (scout_opportunity_signals IS NULL OR jsonb_typeof(scout_opportunity_signals) = 'array'),
    ADD CONSTRAINT ck_ai_task_item_scout_risks_json
        CHECK (scout_risk_signals IS NULL OR jsonb_typeof(scout_risk_signals) = 'array');

CREATE INDEX idx_ai_task_item_scout_input
    ON ai_task_item (lower(scout_keyword), scout_category_id)
    WHERE scout_keyword IS NOT NULL;

COMMENT ON COLUMN ai_task_item.scout_keyword IS
    'SOURCING_SCOUT 的正規化原始字詞；陌生字詞不建立 trend_keyword';
COMMENT ON COLUMN ai_task_item.scout_category_id IS
    'SOURCING_SCOUT 輸入品類；允許 product_id 為 NULL';
COMMENT ON COLUMN ai_task_item.scout_report IS
    'Agent 6 結構化探索報告；不是未驗證的模型 raw response';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ssds_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON sourcing_candidate TO ssds_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ai_task_item TO ssds_app;
    END IF;
END $$;
