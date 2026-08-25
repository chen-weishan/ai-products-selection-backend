-- ===================================================================
-- V904 套用 §5.3.1 同品類百分位換算
-- ===================================================================
--
-- V902 建立分數時，bonus_subtotal 直接等於加權和 Σ(w_i × normalized_i)，
-- 少了 §5.5 計算範例中「76.3 → 91（同品類百分位換算後）」那一步。
-- 換算函式即 §5.3.1：
--
--     normalized(x) = percentile_rank(x, same_category_values) × 100
--     同品類樣本數 < 10 時退回全品類百分位，並降低信心度
--
-- 本檔做三件事：
--   1. 補足「零食」品類的已評分樣本至 10 筆，讓兩條分支都有資料可測
--   2. 依 §5.3.1 重算 bonus_subtotal / final_score / grade
--   3. 依 §5.9 重算 confidence，並同步開團快照
--
-- 【為什麼要補樣本】
-- 原本 8 個品類的已評分筆數都在 1–4 之間，全部低於 10。
-- 也就是說「同品類百分位」這條主要路徑一次都不會被執行到，
-- 全部走 §5.3.1 的退回分支。補到 10 筆之後，零食走同品類、
-- 其餘品類走全品類，前後端都能實際驗證兩種行為與信心度差異。
--
-- 【percentile_rank 採 CUME_DIST 而非 SQL 的 PERCENT_RANK】
-- 兩者定義不同：
--   PERCENT_RANK() = (rank − 1) / (n − 1)：最低的一筆必定得 0
--   CUME_DIST()    = rank / n            ：最低的一筆得 100/n，不會是 0
-- 統計學上的「百分位等級」通常指「小於等於該值的比例」，即 CUME_DIST，
-- 且用 PERCENT_RANK 會讓每個品類的最後一名固定拿 0 分（扣分後仍是 0），
-- 單一樣本的品類更會直接變成 0/0。故採 CUME_DIST。
-- 這個選擇會影響所有分數，若與客戶確認後要改成另一種定義，改這一支即可。
-- ===================================================================


-- ===================================================================
-- 1. 補足零食品類的樣本數
-- ===================================================================
-- 這六筆刻意只有評分所需的最小資料（無圖片、無關聯關鍵字、無評論），
-- 它們的用途是把「零食」的已評分樣本墊到 10 筆，不是拿來當展示品項。

INSERT INTO product (id, name, category_id, supplier_id, cost, suggested_price, margin_rate, moq,
                     season, status, track_type, logistics_condition,
                     shelf_life_days, created_by) VALUES
  (130, '韓國蜂蜜奶油杏仁果', 10, 1,  78.00, 165.00, 0.5273, 200, 'ALL',      'EVALUATING', 'A', '常溫',        300, 1),
  (131, '泰式椰香脆片',       10, 2,  42.00,  89.00, 0.5281, 400, 'SUMMER',   'EVALUATING', 'A', '常溫',        240, 1),
  (132, '日本柚子軟糖',       10, 1,  55.00, 119.00, 0.5378, 300, 'WINTER',   'WATCHING',   'A', '常溫',        365, 6),
  (133, '義式松露洋芋片',     10, 1,  88.00, 189.00, 0.5344, 150, 'ALL',      'EVALUATING', 'A', '常溫｜易碎',  180, 6),
  (134, '港式蛋捲禮盒',       10, 1, 135.00, 289.00, 0.5329, 100, 'FESTIVAL', 'EVALUATING', 'A', '常溫｜易碎',   90, 1),
  (135, '韓式辣味海苔酥',     10, 2,  38.00,  85.00, 0.5529, 500, 'ALL',      'WATCHING',   'A', '常溫',        210, 1);


-- ===================================================================
-- 2. 為這六筆產生評分與因子明細（做法與 V902 相同）
-- ===================================================================

CREATE TEMP TABLE seed904_meta (score_id BIGINT, product_id BIGINT, scene_type VARCHAR(24));
INSERT INTO seed904_meta VALUES
  (200, 130, 'REPLENISHMENT'),
  (201, 131, 'VIRAL'),
  (202, 132, 'SEASONAL'),
  (203, 133, 'REPLENISHMENT'),
  (204, 134, 'FESTIVAL'),
  (205, 135, 'REPLENISHMENT');

CREATE TEMP TABLE seed904_bonus (
    product_id BIGINT, factor_code VARCHAR(32),
    raw_value NUMERIC(12,4), normalized_value NUMERIC(5,2)
);
INSERT INTO seed904_bonus VALUES
  (130, 'TREND', 0.3100, 64.00), (130, 'MARGIN', 0.5273, 80.00),
  (130, 'CVR', 0.0388, 74.00), (130, 'FESTIVAL', 0.2500, 25.00),
  (130, 'CLIMATE', 0.6000, 60.00),

  (131, 'TREND', 1.9500, 89.00), (131, 'MARGIN', 0.5281, 81.00),
  (131, 'CVR', 0.0300, 62.00), (131, 'FESTIVAL', 0.2000, 20.00),
  (131, 'CLIMATE', 0.8800, 88.00),

  (132, 'TREND', 0.5600, 71.00), (132, 'MARGIN', 0.5378, 84.00),
  (132, 'CVR', 0.0266, 56.00), (132, 'FESTIVAL', 0.3000, 30.00),
  (132, 'CLIMATE', 0.7400, 74.00),

  (133, 'TREND', 0.0900, 50.00), (133, 'MARGIN', 0.5344, 83.00),
  (133, 'CVR', 0.0350, 68.00), (133, 'FESTIVAL', 0.1800, 18.00),
  (133, 'CLIMATE', 0.5200, 52.00),

  (134, 'TREND', 0.7200, 76.00), (134, 'MARGIN', 0.5329, 82.00),
  (134, 'CVR', 0.0410, 78.00), (134, 'FESTIVAL', 0.9200, 92.00),
  (134, 'CLIMATE', 0.5800, 58.00),

  (135, 'TREND', -0.0400, 36.00), (135, 'MARGIN', 0.5529, 88.00),
  (135, 'CVR', 0.0222, 47.00), (135, 'FESTIVAL', 0.1500, 15.00),
  (135, 'CLIMATE', 0.5000, 50.00);

CREATE TEMP TABLE seed904_penalty (product_id BIGINT, factor_code VARCHAR(32), penalty_value NUMERIC(5,2));
INSERT INTO seed904_penalty VALUES
  (133, 'LOGISTICS_RISK', 3.00),   -- 易碎
  (134, 'LOGISTICS_RISK', 5.00),   -- 易碎禮盒
  (134, 'INVENTORY_RISK', 4.00);   -- 節慶專屬，效期 90 天

-- 先寫入「加權和」，百分位換算留到第 3 段統一處理
INSERT INTO product_score (id, product_id, weight_version_id, period, scene_type,
                           bonus_subtotal, penalty_subtotal,
                           final_score, grade, confidence, calculated_at)
SELECT m.score_id, m.product_id, 2, to_char(CURRENT_DATE, 'IYYY"W"IW'), m.scene_type,
       agg.raw_sum, agg.penalty,
       GREATEST(0, agg.raw_sum - agg.penalty),
       'C',            -- 分級與信心度都在第 3、4 段依 §5.3.1／§5.9 重算
       100,
       now() - interval '2 days'
FROM seed904_meta m
JOIN LATERAL (
    SELECT ROUND(COALESCE((SELECT SUM(b.normalized_value * wp.weight)
                           FROM seed904_bonus b
                           JOIN weight_profile wp ON wp.version_id = 2
                                                 AND wp.scene_type = m.scene_type
                                                 AND wp.factor_code = b.factor_code
                           WHERE b.product_id = m.product_id), 0), 2) AS raw_sum,
           COALESCE((SELECT SUM(p.penalty_value) FROM seed904_penalty p
                     WHERE p.product_id = m.product_id), 0) AS penalty
) agg ON TRUE;

INSERT INTO score_factor (score_id, factor_code, raw_value, normalized_value, weight,
                          penalty_value, is_imputed, is_penalty, data_available)
SELECT m.score_id, b.factor_code, b.raw_value, b.normalized_value, wp.weight,
       NULL, FALSE, FALSE, TRUE
FROM seed904_bonus b
JOIN seed904_meta m ON m.product_id = b.product_id
JOIN weight_profile wp ON wp.version_id = 2 AND wp.scene_type = m.scene_type
                      AND wp.factor_code = b.factor_code;

INSERT INTO score_factor (score_id, factor_code, raw_value, normalized_value, weight,
                          penalty_value, is_imputed, is_penalty, data_available)
SELECT m.score_id, p.factor_code, NULL, NULL, NULL, p.penalty_value, FALSE, TRUE, TRUE
FROM seed904_penalty p JOIN seed904_meta m ON m.product_id = p.product_id;

DROP TABLE seed904_penalty;
DROP TABLE seed904_bonus;
DROP TABLE seed904_meta;


-- ===================================================================
-- 3. 依 §5.3.1 換算加分小計，並重算分數與分級
-- ===================================================================
-- bonus_subtotal 此刻存的是加權和（V902 與上一段寫入時都是這個值），
-- 換算後才是真正參與 §5.5 減法的加分小計。
--
-- 原本這裡排序用的是 base_score，該欄已於 V17 移除（與 bonus_subtotal 語意
-- 重複，§7.2.6 的欄位表已無此欄）。兩欄的值本來就一直相同，改用
-- bonus_subtotal 排序不影響結果。

WITH sample_size AS (
    -- 每個期別、每個品類的已評分樣本數，決定走哪一條分支
    SELECT s.period, p.category_id, COUNT(*) AS n
    FROM product_score s JOIN product p ON p.id = s.product_id
    GROUP BY s.period, p.category_id
),
ranked AS (
    SELECT s.id,
           ss.n,
           -- 同品類百分位（樣本 >= 10 時採用）
           CUME_DIST() OVER (PARTITION BY s.period, p.category_id ORDER BY s.bonus_subtotal) * 100 AS pct_category,
           -- 全品類百分位（§5.3.1 的退回分支）
           CUME_DIST() OVER (PARTITION BY s.period ORDER BY s.bonus_subtotal) * 100 AS pct_overall
    FROM product_score s
             JOIN product p     ON p.id = s.product_id
             JOIN sample_size ss ON ss.period = s.period AND ss.category_id = p.category_id
),
converted AS (
    SELECT id,
           ROUND((CASE WHEN n >= 10 THEN pct_category ELSE pct_overall END)::numeric, 2) AS bonus
    FROM ranked
)
UPDATE product_score s
SET bonus_subtotal = c.bonus,
    final_score    = GREATEST(0, c.bonus - s.penalty_subtotal),
    grade = CASE
                -- §5.6 硬規則：扣分達 20 以上者分級最高只給 B
                WHEN GREATEST(0, c.bonus - s.penalty_subtotal) >= 85
                     AND s.penalty_subtotal < 20                       THEN 'A'
                WHEN GREATEST(0, c.bonus - s.penalty_subtotal) >= 70   THEN 'B'
                ELSE 'C'
            END
FROM converted c
WHERE s.id = c.id;


-- ===================================================================
-- 4. 依 §5.9 重算信心度
-- ===================================================================
--   基準 100
--   −10  熱度來源降級（INSTAGRAM 目前為 DEGRADED，全品項皆扣）
--   −20  同品類樣本 < 10（即本次走了 §5.3.1 的退回分支）
--   −8   每個缺資料的加分因子
--   −10  情境判定信心 < 0.7（含判定失敗退回 REPLENISHMENT 的情況）

WITH sample_size AS (
    SELECT s.period, p.category_id, COUNT(*) AS n
    FROM product_score s JOIN product p ON p.id = s.product_id
    GROUP BY s.period, p.category_id
),
missing_factors AS (
    SELECT score_id, COUNT(*) AS n_missing
    FROM score_factor WHERE NOT is_penalty AND NOT data_available
    GROUP BY score_id
),
scene AS (
    SELECT DISTINCT ON (product_id) product_id, ai_confidence
    FROM scene_classification_log ORDER BY product_id, created_at DESC
),
calc AS (
    SELECT s.id,
           GREATEST(0, 100
               - 10
               - CASE WHEN ss.n >= 10 THEN 0 ELSE 20 END
               - 8 * COALESCE(mf.n_missing, 0)
               - CASE WHEN sc.product_id IS NULL THEN 0
                      WHEN sc.ai_confidence IS NULL OR sc.ai_confidence < 0.70 THEN 10
                      ELSE 0 END
           ) AS conf
    FROM product_score s
             JOIN product p      ON p.id = s.product_id
             JOIN sample_size ss ON ss.period = s.period AND ss.category_id = p.category_id
             LEFT JOIN missing_factors mf ON mf.score_id = s.id
             LEFT JOIN scene sc  ON sc.product_id = s.product_id
    WHERE s.period = to_char(CURRENT_DATE, 'IYYY"W"IW')
)
UPDATE product_score s SET confidence = calc.conf
FROM calc WHERE calc.id = s.id;

-- 歷史期別沿用該品項當期的信心度，讓走勢圖上的標記不會忽高忽低。
-- 歷史列沒有因子明細，就地重算會少算「缺資料因子」那一項。
UPDATE product_score h
SET confidence = c.confidence
FROM product_score c
WHERE c.period = to_char(CURRENT_DATE, 'IYYY"W"IW')
  AND c.product_id = h.product_id
  AND h.period <> c.period;


-- ===================================================================
-- 5. 開團快照：本檔不再需要同步
-- ===================================================================
-- v3.0 §7.2.8 把 campaign_snapshot 精簡為六欄，分數、分級、加減分小計
-- 全部不再重複保存，改由 decision_record.score_id join 回 product_score。
-- 也就是說第 3 段重算完分數，快照上顯示的值自動就是對的，
-- 沒有第二份資料需要跟著改——這正是那次精簡想解決的問題。


-- ===================================================================
-- 6. 序列收尾
-- ===================================================================
-- 本檔又用了寫死的 id（product 130–135、product_score 200–205），
-- 序列同樣不會自己前進，重跑一次 V903 用過的收尾邏輯。

DO $$
DECLARE
    rec    RECORD;
    seq    TEXT;
    max_id BIGINT;
BEGIN
    FOR rec IN
        SELECT c.relname AS table_name, a.attname AS column_name
        FROM pg_class c
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                 JOIN pg_attribute a ON a.attrelid = c.oid
        WHERE n.nspname = current_schema()
          AND c.relkind = 'r'
          AND a.attidentity IN ('a', 'd')
          -- 已 DROP 的欄位仍留在 pg_attribute 且保有 attidentity，
          -- 名稱會是 '........pg.dropped.N........'。V17 砍掉了
          -- campaign_snapshot.id 與 campaign_result.id，不濾掉的話
          -- 這個迴圈會對著一個不存在的欄名下 SELECT MAX(...) 而失敗。
          AND NOT a.attisdropped
          AND a.attnum > 0
    LOOP
        seq := pg_get_serial_sequence(quote_ident(rec.table_name), rec.column_name);
        CONTINUE WHEN seq IS NULL;
        EXECUTE format('SELECT COALESCE(MAX(%I), 0) FROM %I', rec.column_name, rec.table_name)
            INTO max_id;
        PERFORM setval(seq, max_id + 1, false);
    END LOOP;
END $$;
