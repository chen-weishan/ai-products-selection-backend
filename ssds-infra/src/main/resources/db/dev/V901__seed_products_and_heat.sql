-- ===================================================================
-- V901 開發用假資料（2/3）：品項、關鍵字、趨勢與熱度資料
-- ===================================================================


-- ===================================================================
-- 品項：A 軌（規格書 §7.2 product）
-- ===================================================================
-- 資料刻意涵蓋各種邊界情況，讓後續開發不必自己造資料就能測到：
--   * 六種 status 全部出現
--   * 冷鏈／易碎／材積大／液體 → logistics_risk 扣分的各種觸發條件
--   * 效期 30 天（蛋黃酥）與 1095 天（唇釉）→ inventory_risk 的兩端
--   * 一筆 REJECTED 且填了 reject_reason
--   * 一筆由 B 軌成案轉入的 A 軌品項（sourcing_status = PROMOTED）
--
-- margin_rate 由（售價－成本）／售價 算好直接寫入，與 Product 實體的
-- recalculateMarginRate() 一致；四捨五入到小數第 4 位。

INSERT INTO product (id, name, category_id, supplier_id, cost, suggested_price, margin_rate, moq,
                     season, status, reject_reason, listed_at,
                     track_type, sourcing_status, logistics_condition, shelf_life_days, created_by) VALUES
  (101, '日式抹茶夾心餅乾',       10, 1,   62.00,  129.00, 0.5194, 200, 'SUMMER',   'LISTED',     NULL, CURRENT_DATE - 21, 'A', NULL, '常溫｜夏季易融化',   180, 1),
  (102, '韓式草莓夾心年糕',       10, 1,   48.00,   99.00, 0.5152, 300, 'WINTER',   'ADOPTED',    NULL, NULL,              'A', NULL, '常溫',               120, 1),
  (103, '泰式打拋豬即食調理包',   12, 2,   75.00,  149.00, 0.4966, 150, 'ALL',      'EVALUATING', NULL, NULL,              'A', NULL, '冷藏｜需冷鏈',        45, 1),
  (104, '冷凍舒芙蕾鬆餅',         12, 3,   92.00,  199.00, 0.5377, 100, 'ALL',      'EVALUATING', NULL, NULL,              'A', NULL, '冷凍｜需冷鏈',        90, 6),
  (105, '手作黑糖珍珠鮮奶茶包',   11, 2,   35.00,   89.00, 0.6067, 400, 'ALL',      'LISTED',     NULL, CURRENT_DATE - 45, 'A', NULL, '常溫',               240, 1),
  (106, '冰萃冷泡茶禮盒',         11, 1,  180.00,  380.00, 0.5263,  80, 'SUMMER',   'ADOPTED',    NULL, NULL,              'A', NULL, '常溫',               365, 6),
  (107, '中秋蛋黃酥禮盒',         10, 1,  220.00,  480.00, 0.5417,  60, 'FESTIVAL', 'EVALUATING', NULL, NULL,              'A', NULL, '常溫｜易碎',          30, 1),
  (108, '減醣蒟蒻麵',             12, 3,   40.00,   95.00, 0.5789, 200, 'ALL',      'WATCHING',   NULL, NULL,              'A', NULL, '冷藏',                60, 6),
  (109, '天然酵素洗衣精補充包',   20, 4,   68.00,  139.00, 0.5109, 240, 'ALL',      'LISTED',     NULL, CURRENT_DATE - 60, 'A', NULL, '常溫｜液體重量大',  NULL, 1),
  (110, '竹漿本色抽取衛生紙',     21, 4,  210.00,  359.00, 0.4150,  50, 'ALL',      'LISTED',     NULL, CURRENT_DATE - 90, 'A', NULL, '常溫｜材積大',      NULL, 1),
  (111, '胺基酸溫和洗面乳',       30, 5,  155.00,  320.00, 0.5156, 120, 'ALL',      'ADOPTED',    NULL, NULL,              'A', NULL, '常溫',               730, 6),
  (112, '玻尿酸保濕精華',         30, 5,  260.00,  590.00, 0.5593, 100, 'ALL',      'EVALUATING', NULL, NULL,              'A', NULL, '常溫',               730, 1),
  (113, '霧面絲絨唇釉',           31, 5,   88.00,  249.00, 0.6466, 200, 'ALL',      'WATCHING',   NULL, NULL,              'A', NULL, '常溫',              1095, 6),
  (114, '迷你旋風氣炸鍋',         40, 6,  780.00, 1490.00, 0.4765,  30, 'ALL',      'EVALUATING', NULL, NULL,              'A', NULL, '常溫｜材積大',      NULL, 1),
  (115, '隨行果汁攪拌杯',         40, 6,  320.00,  690.00, 0.5362,  60, 'SUMMER',   'WATCHING',   NULL, NULL,              'A', NULL, '常溫',              NULL, 6),
  (116, '泰式手標奶茶粉',         11, 2,   52.00,  119.00, 0.5630, 500, 'ALL',      'REJECTED',
        '同品類已有兩支開團中的奶茶品項，客層完全重疊；最小訂購量 500 亦超出目前倉儲餘量', NULL,
        'A', NULL, '常溫', 365, 2),
  -- B 軌成案轉入：找到供應商後 track_type 由 B 改 A，sourcing_status 保留為 PROMOTED
  -- 以標示來歷（FR-16-2 成案轉軌）。探索期間累積的熱度資料一併保留（AC-16-5）。
  (117, '低溫烘焙綜合堅果脆片',   10, 1,   95.00,  189.00, 0.4974, 150, 'ALL',      'EVALUATING', NULL, NULL,              'A', 'PROMOTED', '常溫',        270, 1),
  -- 草稿：A 軌但尚未填成本售價。ck_product_track_a_pricing 僅在非 DRAFT 時要求定價
  (118, '氣泡水機（規格待確認）', 40, NULL, NULL,    NULL,   NULL, NULL, 'ALL',      'DRAFT',      NULL, NULL,              'A', NULL, NULL,                NULL, 1);


-- ===================================================================
-- 品項：B 軌（尋源探索中）
-- ===================================================================
-- B 軌沒有供應商與成本，也不產生選品分數（AC-16-2）。
--
-- 五筆涵蓋 sourcing_status 的全部五個值。狀態自 V17 起只存在於本表
-- （§7.2.9 L3043：不重複於 sourcing_candidate），所以這裡的值必須與
-- V903 那四筆候選的時效落差說得通：
--   120 落差 +11 → URGENT     121 落差 −10 → REJECTED
--   122 落差 +26 → SOURCING   123 落差 −18 → REJECTED
--   124 還沒探索過，沒有對應的 sourcing_candidate 列 → PENDING
-- PROMOTED 出現在已轉入 A 軌的品項 117 上。

INSERT INTO product (id, name, category_id, supplier_id, cost, suggested_price, margin_rate, moq,
                     season, status, listed_at,
                     track_type, sourcing_status, logistics_condition, shelf_life_days, created_by) VALUES
  (120, '爆漿雪花糕',       10, NULL, NULL, NULL, NULL, NULL, 'ALL',    'EVALUATING', NULL, 'B', 'URGENT',   NULL, NULL, 1),
  (121, '韓式草莓糖葫蘆',   10, NULL, NULL, NULL, NULL, NULL, 'WINTER', 'EVALUATING', NULL, 'B', 'REJECTED', NULL, NULL, 1),
  (122, '迷你隨身製冰杯',   40, NULL, NULL, NULL, NULL, NULL, 'SUMMER', 'EVALUATING', NULL, 'B', 'SOURCING', NULL, NULL, 6),
  (123, '韓系香水身體噴霧', 30, NULL, NULL, NULL, NULL, NULL, 'ALL',    'WATCHING',   NULL, 'B', 'REJECTED', NULL, NULL, 6),
  -- 尚未探索：還沒有 sourcing_candidate 列，FR-16-2 清單上是「待探索」那一群
  (124, '梅子氣泡飲',       11, NULL, NULL, NULL, NULL, NULL, 'SUMMER', 'EVALUATING', NULL, 'B', 'PENDING',  NULL, NULL, 1);


-- ===================================================================
-- 品項圖片
-- ===================================================================
-- 只存路徑，檔案本體不進資料庫。路徑格式與正式環境一致，
-- 開發時前端會拿到 404，這是預期行為（沒有真的圖檔）。

INSERT INTO product_image (id, product_id, file_path, sort_order) VALUES
  (1, 101, '/uploads/product/101/main.jpg',   0),
  (2, 101, '/uploads/product/101/detail1.jpg', 1),
  (3, 105, '/uploads/product/105/main.jpg',   0),
  (4, 107, '/uploads/product/107/main.jpg',   0),
  (5, 112, '/uploads/product/112/main.jpg',   0);


-- ===================================================================
-- 關鍵字（規格書 §7.2.2 trend_keyword）
-- ===================================================================
-- lifecycle 欄位已於 v3.0 移除（§7.2.2 L2659）：五值列舉與 heat_stage 的
-- 三值、FR-06 內文的三段中文並存，形成三套詞彙。階段資訊改由
-- heat_composite_daily.stage 逐日提供，不在關鍵字上另存一份會過期的快照。
--
-- 但「這個關鍵字的熱度該長什麼形狀」仍是造假資料時必須決定的事，
-- 所以形狀對照改為本檔內的區域資料（見下方 shape CTE），不再進資料庫。

INSERT INTO trend_keyword (id, keyword, geo, last_fetched_at, enabled) VALUES
  (1,  '抹茶夾心餅乾',   'TW', now() - interval '4 hours', TRUE),
  (2,  '草莓年糕',       'TW', now() - interval '4 hours', TRUE),
  (3,  '打拋豬',         'TW', now() - interval '4 hours', TRUE),
  (4,  '舒芙蕾鬆餅',     'TW', now() - interval '4 hours', TRUE),
  (5,  '黑糖珍珠',       'TW', now() - interval '4 hours', TRUE),
  (6,  '冷泡茶',         'TW', now() - interval '4 hours', TRUE),
  (7,  '蛋黃酥',         'TW', now() - interval '4 hours', TRUE),
  (8,  '減醣',           'TW', now() - interval '4 hours', TRUE),
  (9,  '酵素洗衣精',     'TW', now() - interval '4 hours', TRUE),
  (10, '本色衛生紙',     'TW', now() - interval '4 hours', TRUE),
  (11, '胺基酸洗面乳',   'TW', now() - interval '4 hours', TRUE),
  (12, '玻尿酸精華',     'TW', now() - interval '4 hours', TRUE),
  (13, '絲絨唇釉',       'TW', now() - interval '4 hours', TRUE),
  (14, '氣炸鍋',         'TW', now() - interval '4 hours', TRUE),
  (15, '隨行杯果汁機',   'TW', now() - interval '4 hours', TRUE),
  (16, '堅果脆片',       'TW', now() - interval '4 hours', TRUE),
  -- B 軌關鍵字：還沒有供應商，但熱度已經在累積
  (20, '爆漿雪花糕',     'TW', now() - interval '4 hours', TRUE),
  (21, '草莓糖葫蘆',     'TW', now() - interval '4 hours', TRUE),
  (22, '隨身製冰杯',     'TW', now() - interval '4 hours', TRUE),
  (23, '身體噴霧',       'TW', now() - interval '4 hours', TRUE),
  -- 已停用：排程不再採集，但既有熱度資料保留
  (24, '生酮飲食',       'TW', now() - interval '40 days', FALSE);

INSERT INTO product_keyword (product_id, keyword_id) VALUES
  (101, 1), (102, 2), (103, 3), (104, 4), (105, 5), (106, 6), (107, 7),
  (108, 8), (109, 9), (110, 10), (111, 11), (112, 12), (113, 13),
  (114, 14), (115, 15), (117, 16),
  -- 一個品項綁多個關鍵字：熱度取合成值（§5.3.2）
  (105, 8),
  (108, 24),
  (120, 20), (121, 21), (122, 22), (123, 23);


-- ===================================================================
-- 每日合成熱度（規格書 §7.2.3 heat_composite_daily）
-- ===================================================================
-- v3.0 新增的表，取代 v1.0 的 trend_daily（單來源 0–100 熱度，已於 V17 drop）。
-- FR-06 的曲線、§5.3.3 的斜率、§5.8 的階段判定都只認這張表。
--
-- 產生近 90 日資料。手寫 21 個關鍵字 × 90 天不切實際，改用 generate_series
-- 造出「有形狀」的曲線 —— 形狀本身才是重點，一條水平線測不出任何東西。
--
-- 曲線設計（d = 距今天數，0 = 今天）：
--   PEAK      先升後平：近 7 日高檔震盪，30 日前明顯較低 → slope_7d 小、slope_30d 大
--   GROWING   穩定上升 → 兩個斜率都為正
--   EMERGING  近 14 日才起飛 → slope_7d 極大，量級仍低（測 volume_below_floor 的把關）
--   DECLINING 持續下滑 → 兩個斜率都為負
--   STABLE    小幅震盪
--
-- 斜率、階段、階段週數三者都由曲線本身算出來，不另外手填：
-- 手填很容易與曲線對不上，而畫面上兩者是並排顯示的。
--
-- applied_weights 記的是「本次實際採用的合成權重」。INSTAGRAM 在 V900 被設為
-- DEGRADED，其 0.150 依 §5.3.2 歸零並由其餘三來源按比例重新正規化：
--   THREADS 0.350 / 0.850 = 0.412、GOOGLE_TRENDS 0.300 / 0.850 = 0.353、
--   MANUAL  0.200 / 0.850 = 0.235
-- 這個欄位存在的理由就是讓事後看得出來「當天為什麼是這個值」。

INSERT INTO heat_composite_daily
    (keyword_id, stat_date, composite_value, slope_7d, slope_30d, stage, stage_weeks,
     estimated_lifespan_days, applied_weights, divergence_flag, volume_below_floor)
WITH shape (keyword_id, shape) AS (
    VALUES (1, 'PEAK'),      (2, 'GROWING'),    (3, 'STABLE'),   (4, 'DECLINING'),
           (5, 'STABLE'),    (6, 'GROWING'),    (7, 'EMERGING'), (8, 'GROWING'),
           (9, 'STABLE'),    (10, 'STABLE'),    (11, 'STABLE'),  (12, 'GROWING'),
           (13, 'PEAK'),     (14, 'DECLINING'), (15, 'STABLE'),  (16, 'GROWING'),
           (20, 'EMERGING'), (21, 'PEAK'),      (22, 'GROWING'), (23, 'DECLINING'),
           (24, 'DECLINING')
),
curve AS (
    SELECT s.keyword_id,
           s.shape,
           (CURRENT_DATE - d)::date AS stat_date,
           GREATEST(0, LEAST(100, CASE s.shape
               WHEN 'PEAK'      THEN 90 - (d * 0.45)::int + (sin(d / 3.0) * 6)::int
               WHEN 'GROWING'   THEN 72 - (d * 0.55)::int + (sin(d / 4.0) * 5)::int
               WHEN 'EMERGING'  THEN CASE WHEN d <= 14 THEN 58 - (d * 3.2)::int
                                          ELSE 12 - (d * 0.08)::int END
                                     + (sin(d / 2.5) * 3)::int
               WHEN 'DECLINING' THEN 28 + (d * 0.62)::int + (sin(d / 3.5) * 5)::int
               ELSE                  55 + (sin(d / 5.0) * 9)::int - (d * 0.05)::int
           END))::numeric(6, 2) AS composite_value
    FROM shape s
             CROSS JOIN generate_series(0, 89) AS d
    -- 已停用的關鍵字只留 40 天前以外的舊資料，模擬「停止採集後資料就斷了」
    WHERE s.keyword_id <> 24 OR d BETWEEN 40 AND 89
),
-- 雙窗口斜率（§5.3.3）。最早的 7／30 天沒有比較基準，留 NULL 而不是填 0：
-- 0 的意思是「沒有變化」，NULL 的意思是「算不出來」，兩者在畫面上不同。
sloped AS (
    SELECT c.*,
           ROUND(((c.composite_value - LAG(c.composite_value, 7) OVER w)
               / NULLIF(LAG(c.composite_value, 7) OVER w, 0))::numeric, 4)  AS slope_7d,
           ROUND(((c.composite_value - LAG(c.composite_value, 30) OVER w)
               / NULLIF(LAG(c.composite_value, 30) OVER w, 0))::numeric, 4) AS slope_30d
    FROM curve c
    WINDOW w AS (PARTITION BY c.keyword_id ORDER BY c.stat_date)
),
staged AS (
    SELECT s.*,
           CASE
               WHEN s.slope_7d IS NULL THEN 'PLATEAU'
               WHEN s.slope_7d > 0.10  THEN 'RISING'
               WHEN s.slope_7d < -0.10 THEN 'DECLINING'
               ELSE                         'PLATEAU'
           END AS stage
    FROM sloped s
),
-- 階段週數＝距離「這個階段開始的那一天」的完整週數。
-- 用 gaps-and-islands 算：先標出階段換手的那幾天，再累加成群組編號。
-- PostgreSQL 不允許窗函數巢狀，所以標記與累加必須拆成兩層。
marked AS (
    SELECT st.*,
           CASE WHEN st.stage IS DISTINCT FROM
                     LAG(st.stage) OVER (PARTITION BY st.keyword_id ORDER BY st.stat_date)
                THEN 1 ELSE 0 END AS stage_changed
    FROM staged st
),
islands AS (
    SELECT m.*,
           SUM(m.stage_changed) OVER (PARTITION BY m.keyword_id ORDER BY m.stat_date
               ROWS UNBOUNDED PRECEDING) AS stage_group
    FROM marked m
)
SELECT i.keyword_id,
       i.stat_date,
       i.composite_value,
       i.slope_7d,
       i.slope_30d,
       i.stage,
       FLOOR((i.stat_date - MIN(i.stat_date) OVER (PARTITION BY i.keyword_id, i.stage_group)) / 7.0)::smallint,
       -- §5.8 的壽命查表值，與 V903 的 sourcing_candidate 用同一組數字
       CASE i.shape
           WHEN 'EMERGING'  THEN 56
           WHEN 'GROWING'   THEN 56
           WHEN 'PEAK'      THEN 35
           WHEN 'DECLINING' THEN 17
           ELSE                  90
       END,
       '{"THREADS": 0.412, "GOOGLE_TRENDS": 0.353, "INSTAGRAM": 0.000, "MANUAL": 0.235}'::jsonb,
       -- 背離：兩個窗口的方向相反，通常是見頂訊號
       (i.slope_7d IS NOT NULL AND i.slope_30d IS NOT NULL
           AND sign(i.slope_7d) <> sign(i.slope_30d)),
       -- 量級下限（§5.2.1-a）：熱度太低時 TREND 因子不該加分
       i.composite_value < 20
FROM islands i;


-- ===================================================================
-- 各來源熱度讀值（規格書 §7.2.3 heat_reading、§5.3.2）
-- ===================================================================
-- 重點是「各來源量級不可比」：THREADS 存的是討論則數（幾百到幾千），
-- GOOGLE_TRENDS 是 0–100 的指數，INSTAGRAM 是貼文數。
-- 直接相加毫無意義，所以先各自百分位化再依權重合成。
--
-- 因果上是「先有讀值才合成」，但造假資料時反過來比較實際：
-- 先設計出有形狀的合成曲線，再回推各來源的讀值，兩張表才對得起來。
--
-- INSTAGRAM 是品類級來源（V900 的 granularity = 'CATEGORY'），所以它的列
-- 填的是 category_id 而非 keyword_id —— 這正是 v3.0 為 heat_reading 加上
-- category_id 的理由：v2.0 只有關鍵字維度，IG 的品類級讀值無處落地。
--
-- 只產生近 30 日；MANUAL 來源不進本表，走 manual_heat_tag。

INSERT INTO heat_reading (source_id, keyword_id, category_id, reading_date, raw_value, percentile_within_source)
WITH raw AS (
    -- 關鍵字級來源
    SELECT s.id         AS source_id,
           h.keyword_id,
           NULL::bigint AS category_id,
           h.stat_date  AS reading_date,
           CASE s.source_code
               -- 討論則數：熱度指數的線性放大，讓量級和 Trends 完全不同
               WHEN 'THREADS' THEN h.composite_value * 18 + 40
               -- Google Trends 本來就是 0–100 指數
               ELSE                h.composite_value
           END          AS raw_value
    FROM heat_composite_daily h
             CROSS JOIN heat_source s
    WHERE s.granularity = 'KEYWORD'
      AND s.adapter_type = 'REST'
      AND h.stat_date >= CURRENT_DATE - 30

    UNION ALL

    -- 品類級來源：以該品類底下所有品項關聯到的關鍵字取平均，換算成貼文數
    SELECT s.id, NULL::bigint, c.category_id, c.stat_date, c.avg_value * 4 + 12
    FROM (SELECT p.category_id,
                 h.stat_date,
                 AVG(h.composite_value) AS avg_value
          FROM heat_composite_daily h
                   JOIN product_keyword pk ON pk.keyword_id = h.keyword_id
                   JOIN product p ON p.id = pk.product_id
          WHERE h.stat_date >= CURRENT_DATE - 30
          GROUP BY p.category_id, h.stat_date) c
             CROSS JOIN heat_source s
    WHERE s.source_code = 'INSTAGRAM'
)
SELECT source_id,
       keyword_id,
       category_id,
       reading_date,
       ROUND(raw_value, 3),
       ROUND((PERCENT_RANK() OVER (PARTITION BY source_id ORDER BY raw_value) * 100)::numeric, 2)
FROM raw;


-- ===================================================================
-- 人工熱度標記（規格書 §7.2 manual_heat_tag、FR-14-1）
-- ===================================================================
-- 只存連結與評級，不擷取頁面內容。
--
-- 資料刻意安排成三種衰減狀態，讓 AC-14-2 可以直接驗證：
--   * 5 天前  → 權重 ×1.0（有效）
--   * 20 天前 → 權重 ×0.5（半衰）
--   * 40 天前 → 權重 ×0（失效，畫面應顯示為已過期）
-- 並且讓品項 101 有兩位標記者、品項 120 只有一位，
-- 對應 §5.3.2 的信心係數 0.8 與 0.6。

INSERT INTO manual_heat_tag (id, source_url, platform, heat_level, product_id, keyword_id, observed_at, tagged_by, note) VALUES
  -- 品項 101：兩位標記者 → 信心係數 0.8
  (1, 'https://www.threads.net/@foodie_tw/post/C8xAbCdEfGh',        'THREADS',     5, 101, NULL, now() - interval '2 days',  1, '同一天看到三個不同帳號在推，留言區都在問哪裡買'),
  (2, 'https://www.instagram.com/p/C8yZzYyXxWw/',                    'INSTAGRAM',   4, 101, NULL, now() - interval '5 days',  6, '限動轉發量明顯偏高'),
  -- 半衰期樣本：20 天前，權重應為 0.5
  (3, 'https://www.tiktok.com/@snackhunter/video/7412345678901234', 'TIKTOK',      4, 102, NULL, now() - interval '20 days', 1, '影片破 30 萬讚'),
  -- 已失效樣本：40 天前，權重應為 0
  (4, 'https://www.facebook.com/groups/1234567890/posts/9876543210', 'FACEBOOK',    3, 104, NULL, now() - interval '40 days', 1, '社團討論，熱度當時就普通'),
  -- B 軌關鍵字標記：單一標記者 → 信心係數 0.6
  (5, 'https://www.xiaohongshu.com/explore/66f0a1b2c3d4e5f60718',    'XIAOHONGSHU', 5, NULL, 20, now() - interval '3 days',  1, '小紅書洗版等級，台灣還沒看到有人賣'),
  (6, 'https://www.threads.net/@sweettooth/post/C8mNoPqRsTu',        'THREADS',     4, NULL, 21, now() - interval '6 days',  6, '冬季限定，去年同期也曾竄起'),
  (7, 'https://www.tiktok.com/@gadgetgirl/video/7423456789012345',   'TIKTOK',      3, NULL, 22, now() - interval '8 days',  1, '夏季小物，討論度中等'),
  (8, 'https://www.instagram.com/p/C9aBbCcDdEe/',                    'INSTAGRAM',   2, NULL, 23, now() - interval '12 days', 6, '熱度明顯已過，僅零星討論'),
  (9, 'https://www.threads.net/@dailycook/post/C9fGhIjKlMn',         'THREADS',     4, 117, NULL, now() - interval '4 days',  1, '健康零食話題帶動，討論穩定成長');
