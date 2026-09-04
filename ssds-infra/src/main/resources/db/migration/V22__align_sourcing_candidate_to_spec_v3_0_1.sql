-- ===================================================================
-- V22 B 軌尋源資料模型對齊 v3.0.1
-- 對應：§5.3.3、§5.8、§7.2.9、AC-16-8～AC-16-10
-- ===================================================================

-- §FR-06 基準層先產生 RULE 值，Agent 5 增益層得覆寫為 AGENT。
ALTER TABLE heat_composite_daily
    ADD COLUMN stage_source VARCHAR(8) NOT NULL DEFAULT 'RULE',
    ADD COLUMN lifespan_source VARCHAR(8) NOT NULL DEFAULT 'RULE',
    ADD CONSTRAINT ck_heat_composite_stage_source
        CHECK (stage_source IN ('RULE', 'AGENT')),
    ADD CONSTRAINT ck_heat_composite_lifespan_source
        CHECK (lifespan_source IN ('RULE', 'AGENT'));

-- 時效落差仍保留為可排序的物化欄位，但其壽命來源改為
-- driving_keyword_id 對應的 heat_composite_daily 當日列。
ALTER TABLE sourcing_candidate
    ADD COLUMN driving_keyword_id BIGINT REFERENCES trend_keyword (id) ON DELETE SET NULL;

COMMENT ON COLUMN sourcing_candidate.driving_keyword_id IS
    '§5.3.3 多關鍵字 trend_raw 取最大值後的生效關鍵字；由 §5.8 每日規則式重算更新';

-- 既有候選不可留下「有落差但不知道由哪支曲線算出」的半套狀態。
-- 依與每日作業相同的規則做一次部署時重算：至少七筆、取最新列、
-- trend_raw = 0.7*slope_7d + 0.3*slope_30d，並以 keyword_id 打破平手。
WITH latest AS (
    SELECT DISTINCT ON (h.keyword_id)
           h.keyword_id, h.slope_7d, h.slope_30d, h.estimated_lifespan_days
    FROM heat_composite_daily h
    WHERE h.slope_7d IS NOT NULL
      AND h.slope_30d IS NOT NULL
      AND h.estimated_lifespan_days IS NOT NULL
      AND (SELECT count(*) FROM heat_composite_daily history
           WHERE history.keyword_id = h.keyword_id) >= 7
    ORDER BY h.keyword_id, h.stat_date DESC
), ranked AS (
    SELECT sc.id AS candidate_id,
           latest.keyword_id,
           latest.estimated_lifespan_days,
           row_number() OVER (
               PARTITION BY sc.id
               ORDER BY (0.7 * latest.slope_7d + 0.3 * latest.slope_30d) DESC,
                        latest.keyword_id ASC) AS rank_no
    FROM sourcing_candidate sc
    JOIN product_keyword pk ON pk.product_id = sc.product_id
    JOIN latest ON latest.keyword_id = pk.keyword_id
)
UPDATE sourcing_candidate sc
SET driving_keyword_id = ranked.keyword_id,
    time_gap_days = ranked.estimated_lifespan_days - sc.lead_time_days
FROM ranked
WHERE ranked.candidate_id = sc.id
  AND ranked.rank_no = 1;

UPDATE sourcing_candidate
SET time_gap_days = NULL
WHERE driving_keyword_id IS NULL;

-- REJECTED 是終態，PROMOTED 已轉入 A 軌；其餘候選依本次重算更新。
UPDATE product p
SET sourcing_status = CASE
    WHEN sc.time_gap_days IS NULL THEN 'PENDING'
    WHEN sc.time_gap_days < 0 THEN 'REJECTED'
    WHEN sc.time_gap_days <= 14 THEN 'URGENT'
    ELSE 'SOURCING'
END
FROM sourcing_candidate sc
WHERE sc.product_id = p.id
  AND p.track_type = 'B'
  AND p.sourcing_status NOT IN ('PROMOTED', 'REJECTED')
  AND p.deleted_at IS NULL;

-- 舊約束直接引用 estimated_lifespan_days，必須先移除。
ALTER TABLE sourcing_candidate
    DROP CONSTRAINT IF EXISTS ck_sourcing_gap;

-- v3.0.1 明文移除探索當下的熱度快照；唯一權威來源為逐日合成表。
ALTER TABLE sourcing_candidate
    DROP COLUMN heat_stage,
    DROP COLUMN stage_weeks,
    DROP COLUMN estimated_lifespan_days;

CREATE INDEX idx_sourcing_driving_keyword
    ON sourcing_candidate (driving_keyword_id);

-- V16 已存在的受限應用角色要能讀寫新欄位；不存在時（例如只跑部分
-- migration 的測試庫）安全略過。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ssds_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON heat_composite_daily TO ssds_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON sourcing_candidate TO ssds_app;
    END IF;
END $$;
