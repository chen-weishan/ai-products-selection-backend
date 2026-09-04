-- ===================================================================
-- V908 將既有 V903 尋源假資料轉為 V22 的 v3.0.1 資料模型。
--
-- keyword_id 只代表最初探索來源；driving_keyword_id 必須從該 Product
-- 目前關聯的所有關鍵字中，依最新 trend_raw 選出。時效落差再使用同一
-- 列 heat_composite_daily 的 estimated_lifespan_days 計算。
-- ===================================================================

WITH latest AS (
    SELECT DISTINCT ON (h.keyword_id)
           h.keyword_id,
           h.slope_7d,
           h.slope_30d,
           h.estimated_lifespan_days
    FROM heat_composite_daily h
    WHERE h.slope_7d IS NOT NULL
      AND h.slope_30d IS NOT NULL
      AND h.estimated_lifespan_days IS NOT NULL
      AND (SELECT count(*)
           FROM heat_composite_daily history
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

-- REJECTED、PROMOTED 為人工／流程終態，不由每日重算覆蓋。
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

-- V899 的三欄只為保留舊 V903 checksum 而暫時存在；最終 schema 不留快照。
ALTER TABLE sourcing_candidate
    DROP COLUMN IF EXISTS heat_stage,
    DROP COLUMN IF EXISTS stage_weeks,
    DROP COLUMN IF EXISTS estimated_lifespan_days;
