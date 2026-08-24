-- ===================================================================
-- V902 開發用假資料（3/4）：權重版本、評分、因子明細、AI 洞察、風險示警
-- ===================================================================
--
-- 【本檔的核心原則：分數不是手寫的，是算出來的】
-- product_score 的 bonus_subtotal 與 score_factor 的每一列必須互相對得起來，
-- 否則 FR-05「分數組成」畫面上的長條加起來不等於總分，
-- 開發同學會以為程式有 bug。所以這裡只手寫「正規化值」與「扣分值」，
-- 權重從 weight_profile 撈、加分小計由 SQL 依 §5.5 公式算出來。
--
-- 順帶把 §5.7 的降級也做進去：某因子 data_available = FALSE 時，
-- 其權重按比例分攤給其餘因子（而不是當成 0 分），這正是規格要求的
-- 「資料不足不懲罰」。新品項與 B 軌品項天生缺歷史資料，
-- 若以扣分處理，這些品項將永遠無法進入排行前段。
-- ===================================================================


-- ===================================================================
-- 權重版本（規格書 §7.2 weight_version、FR-08）
-- ===================================================================
-- 三個版本涵蓋完整生命週期：已退役 / 生效中 / 校準後產生的草稿。
--
-- 生效版本的判準自 V17 起由 status 改為 is_current 旗標（§7.2.5）：
-- status 只有 DRAFT／APPROVED／RETIRED 三值，「已核准」與「現在生效中」
-- 是兩件事——同一版可以核准後先不生效。資料庫端的 partial unique index
-- 保證同時只有一筆 is_current。
--
-- 分級門檻不再存於本表（v3.0 §7.2.5 已無純量門檻欄），改由 grade_threshold
-- 逐榜保存，於 V905 一併寫入。
-- source_calibration_id 於 V903 建立 calibration_report 之後回填。

INSERT INTO weight_version (id, version_no, name, status, effective_from, is_current,
                            category_override_json, change_note,
                            approved_by, approved_at, created_by, created_at) VALUES
  (1, 'v1', '初版｜規格書建議值', 'RETIRED',
   CURRENT_DATE - 180, FALSE, NULL,
   '依規格書 FR-08 初始建議值建立，未經校準',
   2, now() - interval '181 days', 3, now() - interval '185 days'),

  (2, 'v2', '2026 夏季｜提高熱度佔比', 'APPROVED',
   CURRENT_DATE - 60, TRUE,
   -- 類別專屬覆寫：生鮮冷凍的毛利率普遍偏低，用全品類的標準去比會全軍覆沒
   '{"12": {"MARGIN": 0.25, "CVR": 0.45}}'::jsonb,
   '依 2026Q1 回測結果，話題爆款型的熱度斜率由 0.50 調高至 0.55；生鮮冷凍類別另設覆寫',
   2, now() - interval '62 days', 3, now() - interval '70 days'),

  (3, 'v3', '2026Q3 校準建議（草稿）', 'DRAFT',
   NULL, FALSE, NULL,
   'FR-15 季度校準產出：迴歸顯示客群價格帶適配（PRICE_FIT）具顯著預測力，建議納入權重；待 BUYER_LEAD 核准',
   NULL, NULL, 3, now() - interval '5 days');


-- ===================================================================
-- 情境權重組（規格書 §7.2 weight_profile、FR-08）
-- ===================================================================
-- FR-08 表列的四組初始建議值。AC-08-1：每組加總須為 1.000。
--
--   權重組       熱度斜率  毛利率  轉換率  節慶窗  氣候
--   話題爆款型     55%     10%    10%     15%    10%
--   節慶檔期型     20%     20%    20%     30%    10%
--   常態補貨型     15%     35%    35%      5%    10%
--   季節導向型     20%     25%    20%      5%    30%
--
-- 註：v3.0 的六個加分因子中，PRICE_FIT 未被 FR-08 的初始權重表分配權重
-- （四組皆只列五個因子）。此處忠實照表建立，v3 草稿才依校準結果納入
-- PRICE_FIT —— 這也是 FR-15 校準機制存在的意義。
--
-- HEAT_VOLUME 不在 v3.0 的六個加分因子內：§5.2.1-a 已把熱度量級降為
-- 門檻條件（volume_below_floor），不進權重。

INSERT INTO weight_profile (version_id, scene_type, factor_code, weight)
SELECT v.id, w.scene_type, w.factor_code, w.weight
FROM (VALUES
    ('VIRAL',    'TREND',  0.550), ('VIRAL',    'MARGIN',     0.100),
    ('VIRAL',    'CVR',  0.100), ('VIRAL',    'FESTIVAL',   0.150),
    ('VIRAL',    'CLIMATE',     0.100),
    ('FESTIVAL',       'TREND',  0.200), ('FESTIVAL',       'MARGIN',     0.200),
    ('FESTIVAL',       'CVR',  0.200), ('FESTIVAL',       'FESTIVAL',   0.300),
    ('FESTIVAL',       'CLIMATE',     0.100),
    ('REPLENISHMENT', 'TREND',  0.150), ('REPLENISHMENT', 'MARGIN',     0.350),
    ('REPLENISHMENT', 'CVR',  0.350), ('REPLENISHMENT', 'FESTIVAL',   0.050),
    ('REPLENISHMENT', 'CLIMATE',     0.100),
    ('SEASONAL',       'TREND',  0.200), ('SEASONAL',       'MARGIN',     0.250),
    ('SEASONAL',       'CVR',  0.200), ('SEASONAL',       'FESTIVAL',   0.050),
    ('SEASONAL',       'CLIMATE',     0.300)
) AS w(scene_type, factor_code, weight)
         CROSS JOIN (VALUES (1), (2)) AS v(id);

-- v3 草稿：校準建議把各榜的熱度權重拆出一部分給 PRICE_FIT。
-- 依據是 §5.2.4 —— 客群價格帶適配是全系統唯一對應痛點 P-3 的因子，
-- 而 v3.0 新增的 audience_segment／category_audience_mix 讓它終於算得出來。
-- 每一組仍加總為 1.000（AC-08-1）。
INSERT INTO weight_profile (version_id, scene_type, factor_code, weight) VALUES
  (3, 'VIRAL',         'TREND',    0.420), (3, 'VIRAL',         'PRICE_FIT', 0.130),
  (3, 'VIRAL',         'MARGIN',   0.100), (3, 'VIRAL',         'CVR',       0.100),
  (3, 'VIRAL',         'FESTIVAL', 0.150), (3, 'VIRAL',         'CLIMATE',   0.100),
  (3, 'FESTIVAL',      'TREND',    0.150), (3, 'FESTIVAL',      'PRICE_FIT', 0.050),
  (3, 'FESTIVAL',      'MARGIN',   0.200), (3, 'FESTIVAL',      'CVR',       0.200),
  (3, 'FESTIVAL',      'FESTIVAL', 0.300), (3, 'FESTIVAL',      'CLIMATE',   0.100),
  (3, 'REPLENISHMENT', 'TREND',    0.120), (3, 'REPLENISHMENT', 'PRICE_FIT', 0.030),
  (3, 'REPLENISHMENT', 'MARGIN',   0.350), (3, 'REPLENISHMENT', 'CVR',       0.350),
  (3, 'REPLENISHMENT', 'FESTIVAL', 0.050), (3, 'REPLENISHMENT', 'CLIMATE',   0.100),
  (3, 'SEASONAL',      'TREND',    0.160), (3, 'SEASONAL',      'PRICE_FIT', 0.040),
  (3, 'SEASONAL',      'MARGIN',   0.250), (3, 'SEASONAL',      'CVR',       0.200),
  (3, 'SEASONAL',      'FESTIVAL', 0.050), (3, 'SEASONAL',      'CLIMATE',   0.300);


-- ===================================================================
-- 評分：先把「人工決定的部分」放進暫存表
-- ===================================================================

CREATE TEMP TABLE seed_score_meta (
    score_id   BIGINT,
    product_id BIGINT,
    scene_type VARCHAR(24),
    confidence INT
);

-- confidence 依 §5.9 逐項扣分推算，不是隨手填的：
--   基準 100
--   −10  熱度來源降級（INSTAGRAM 目前為 DEGRADED，故所有品項皆扣）
--   −20  同品類樣本 < 10（小家電 40、彩妝 31 只有一兩筆）
--   −8   每個缺資料的加分因子
--   −10  情境判定信心 < 0.7
INSERT INTO seed_score_meta VALUES
  (1,  101, 'VIRAL',    90),
  (2,  102, 'SEASONAL',       90),
  (3,  103, 'REPLENISHMENT', 90),
  (4,  104, 'REPLENISHMENT', 90),
  (5,  105, 'REPLENISHMENT', 90),
  (6,  106, 'SEASONAL',       90),
  (7,  107, 'FESTIVAL',       90),
  (8,  108, 'REPLENISHMENT', 90),
  (9,  109, 'REPLENISHMENT', 90),
  (10, 110, 'REPLENISHMENT', 90),
  (11, 111, 'REPLENISHMENT', 90),
  (12, 112, 'REPLENISHMENT', 82),   -- −8：CONVERSION 無資料
  (13, 113, 'VIRAL',    70),   -- −20：彩妝類樣本不足 10 筆
  (14, 114, 'REPLENISHMENT', 44),   -- −20 樣本 −16 兩個因子缺 −10 情境信心 0.62
  (15, 115, 'SEASONAL',       70),   -- −20：小家電類樣本不足
  (16, 116, 'REPLENISHMENT', 90),
  (17, 117, 'VIRAL',    82);   -- −8：B 軌轉入，無歷史開團紀錄

-- 加分因子的正規化值（0–100，同品類百分位）。
-- data_available = FALSE 者不填 normalized_value，權重會分攤給其餘因子（§5.7）。
CREATE TEMP TABLE seed_bonus (
    product_id       BIGINT,
    factor_code      VARCHAR(32),
    raw_value        NUMERIC(12, 4),
    normalized_value NUMERIC(5, 2),
    data_available   BOOLEAN DEFAULT TRUE,
    is_imputed       BOOLEAN DEFAULT FALSE
);

INSERT INTO seed_bonus (product_id, factor_code, raw_value, normalized_value) VALUES
  -- 101 日式抹茶夾心餅乾：社群竄升中的話題爆款
  (101, 'TREND', 3.4012, 96.00), (101, 'MARGIN', 0.5194, 76.00),
  (101, 'CVR', 0.0412, 81.00), (101, 'FESTIVAL', 0.8800, 88.00),
  (101, 'CLIMATE', 0.9300, 93.00),
  -- 102 韓式草莓夾心年糕：冬季限定
  (102, 'TREND', 1.2400, 78.00), (102, 'MARGIN', 0.5152, 74.00),
  (102, 'CVR', 0.0350, 70.00), (102, 'FESTIVAL', 0.3000, 30.00),
  (102, 'CLIMATE', 0.8800, 88.00),
  -- 103 泰式打拋豬即食調理包
  (103, 'TREND', 0.1200, 52.00), (103, 'MARGIN', 0.4966, 66.00),
  (103, 'CVR', 0.0388, 74.00), (103, 'FESTIVAL', 0.2000, 20.00),
  (103, 'CLIMATE', 0.5500, 55.00),
  -- 104 冷凍舒芙蕾鬆餅：分數不低，但冷鏈與負評把它壓下去
  (104, 'TREND', 0.3100, 68.00), (104, 'MARGIN', 0.5377, 90.00),
  (104, 'CVR', 0.0455, 88.00), (104, 'FESTIVAL', 0.4000, 40.00),
  (104, 'CLIMATE', 0.7200, 72.00),
  -- 105 手作黑糖珍珠鮮奶茶包：常態補貨型的模範生
  (105, 'TREND', 0.2200, 72.00), (105, 'MARGIN', 0.6067, 95.00),
  (105, 'CVR', 0.0520, 96.00), (105, 'FESTIVAL', 0.3000, 30.00),
  (105, 'CLIMATE', 0.7000, 70.00),
  -- 106 冰萃冷泡茶禮盒
  (106, 'TREND', 0.6800, 74.00), (106, 'MARGIN', 0.5263, 78.00),
  (106, 'CVR', 0.0301, 64.00), (106, 'FESTIVAL', 0.3500, 35.00),
  (106, 'CLIMATE', 0.9000, 90.00),
  -- 107 中秋蛋黃酥禮盒：節慶時間窗正值黃金備貨期
  (107, 'TREND', 1.8500, 88.00), (107, 'MARGIN', 0.5417, 86.00),
  (107, 'CVR', 0.0430, 84.00), (107, 'FESTIVAL', 0.9800, 98.00),
  (107, 'CLIMATE', 0.6200, 62.00),
  -- 108 減醣蒟蒻麵
  (108, 'TREND', 0.4400, 66.00), (108, 'MARGIN', 0.5789, 88.00),
  (108, 'CVR', 0.0244, 52.00), (108, 'FESTIVAL', 0.2200, 22.00),
  (108, 'CLIMATE', 0.5600, 56.00),
  -- 109 天然酵素洗衣精補充包
  (109, 'TREND', 0.0300, 48.00), (109, 'MARGIN', 0.5109, 75.00),
  (109, 'CVR', 0.0610, 90.00), (109, 'FESTIVAL', 0.1500, 15.00),
  (109, 'CLIMATE', 0.5000, 50.00),
  -- 110 竹漿本色抽取衛生紙：轉換率極高但毛利率偏低
  (110, 'TREND', 0.0100, 45.00), (110, 'MARGIN', 0.4150, 58.00),
  (110, 'CVR', 0.0702, 94.00), (110, 'FESTIVAL', 0.1200, 12.00),
  (110, 'CLIMATE', 0.4800, 48.00),
  -- 111 胺基酸溫和洗面乳
  (111, 'TREND', 0.1500, 62.00), (111, 'MARGIN', 0.5156, 88.00),
  (111, 'CVR', 0.0566, 92.00), (111, 'FESTIVAL', 0.2000, 20.00),
  (111, 'CLIMATE', 0.5500, 55.00),
  -- 113 霧面絲絨唇釉：CONVERSION 為缺值填補（退回全品類百分位，§5.3.1）
  (113, 'TREND', 2.6000, 92.00), (113, 'MARGIN', 0.6466, 95.00),
  (113, 'FESTIVAL', 0.3000, 30.00),  (113, 'CLIMATE', 0.6000, 60.00),
  -- 115 隨行果汁攪拌杯
  (115, 'TREND', 0.1800, 58.00), (115, 'MARGIN', 0.5362, 80.00),
  (115, 'CVR', 0.0201, 45.00), (115, 'FESTIVAL', 0.2000, 20.00),
  (115, 'CLIMATE', 0.8600, 86.00),
  -- 116 泰式手標奶茶粉：已淘汰
  (116, 'TREND', -0.0800, 30.00), (116, 'MARGIN', 0.5630, 82.00),
  (116, 'CVR', 0.0166, 40.00), (116, 'FESTIVAL', 0.1500, 15.00),
  (116, 'CLIMATE', 0.4500, 45.00);

-- 113 的 CONVERSION：有值但是填補來的（同品類樣本不足，退回全品類百分位）
INSERT INTO seed_bonus (product_id, factor_code, raw_value, normalized_value, data_available, is_imputed) VALUES
  (113, 'CVR', 0.0250, 50.00, TRUE, TRUE);

-- 缺資料的因子：不填 normalized_value，權重分攤給其餘因子，且不扣分（§5.7）
INSERT INTO seed_bonus (product_id, factor_code, raw_value, normalized_value, data_available, is_imputed) VALUES
  -- 112 玻尿酸保濕精華：新品，尚無開團紀錄
  (112, 'TREND', 1.1000, 88.00, TRUE,  FALSE),
  (112, 'MARGIN',     0.5593, 98.00, TRUE,  FALSE),
  (112, 'CVR', NULL,   NULL,  FALSE, FALSE),
  (112, 'FESTIVAL',   0.2500, 25.00, TRUE,  FALSE),
  (112, 'CLIMATE',    0.7000, 70.00, TRUE,  FALSE),
  -- 114 迷你旋風氣炸鍋：兩個因子皆缺
  (114, 'TREND', -0.2100, 40.00, TRUE,  FALSE),
  (114, 'MARGIN',      0.4765, 62.00, TRUE,  FALSE),
  (114, 'CVR',  NULL,   NULL,  FALSE, FALSE),
  (114, 'FESTIVAL',    NULL,   NULL,  FALSE, FALSE),
  (114, 'CLIMATE',     0.4500, 45.00, TRUE,  FALSE),
  -- 117 低溫烘焙綜合堅果脆片：B 軌成案轉入，探索期間沒有開團資料
  (117, 'TREND', 2.1000, 90.00, TRUE,  FALSE),
  (117, 'MARGIN',     0.4974, 70.00, TRUE,  FALSE),
  (117, 'CVR', NULL,   NULL,  FALSE, FALSE),
  (117, 'FESTIVAL',   0.2500, 25.00, TRUE,  FALSE),
  (117, 'CLIMATE',    0.6500, 65.00, TRUE,  FALSE);

-- 扣分因子（§5.2.2，固定生效、不參與權重）
CREATE TEMP TABLE seed_penalty (
    product_id    BIGINT,
    factor_code   VARCHAR(32),
    raw_value     NUMERIC(12, 4),
    penalty_value NUMERIC(5, 2)
);

INSERT INTO seed_penalty VALUES
  (101, 'LOGISTICS_RISK', NULL,   4.00),   -- 夏季高溫易融化
  (102, 'INVENTORY_RISK', 120,    3.00),   -- 季節性強，效期 120 天
  (103, 'LOGISTICS_RISK', NULL,   8.00),   -- 冷藏需冷鏈
  (103, 'INVENTORY_RISK', 45,     4.00),   -- 效期僅 45 天
  (104, 'REVIEW_RISK',    0.2200, 12.00),  -- 負評率 22%，且集中於物流破損
  (104, 'LOGISTICS_RISK', NULL,  10.00),   -- 冷凍需冷鏈，達上限
  (104, 'INVENTORY_RISK', 90,     2.00),
  (107, 'LOGISTICS_RISK', NULL,   5.00),   -- 易碎
  (107, 'INVENTORY_RISK', 30,     4.00),   -- 效期僅 30 天
  (108, 'LOGISTICS_RISK', NULL,   6.00),   -- 冷藏
  (108, 'INVENTORY_RISK', 60,     4.00),
  (109, 'LOGISTICS_RISK', NULL,   3.00),   -- 液體重量大
  (110, 'LOGISTICS_RISK', NULL,   5.00),   -- 材積大
  (114, 'LOGISTICS_RISK', NULL,   6.00),   -- 材積大
  (116, 'REVIEW_RISK',    0.1800, 14.00),  -- 負評率 18%，超過零食類門檻 15%
  (116, 'INVENTORY_RISK', 365,    6.00);   -- 最小訂購量 500 偏高


-- ===================================================================
-- 依 §5.5 公式算出分數並寫入
-- ===================================================================

-- 有效權重：原始權重除以「有資料因子的權重總和」，
-- 這就是 §5.7 所說的「權重按比例分攤給其餘因子」。
-- 全部因子都有資料時分母為 1，有效權重等於原始權重。
CREATE TEMP TABLE seed_effective_weight AS
SELECT b.product_id,
       b.factor_code,
       b.raw_value,
       b.normalized_value,
       b.data_available,
       b.is_imputed,
       CASE WHEN b.data_available
            THEN ROUND(wp.weight / SUM(CASE WHEN b.data_available THEN wp.weight ELSE 0 END)
                                       OVER (PARTITION BY b.product_id), 3)
       END AS effective_weight
FROM seed_bonus b
         JOIN seed_score_meta m  ON m.product_id = b.product_id
         JOIN weight_profile  wp ON wp.version_id = 2
                                AND wp.scene_type = m.scene_type
                                AND wp.factor_code = b.factor_code;

-- base_score 與 risk_penalty 兩個 v1.0 欄位已於 V17 移除（與 bonus_subtotal／
-- penalty_subtotal 語意重複，§5.5 的公式只用後兩者）。
--
-- 這裡的分級先用 85／70 這組初版門檻算，V905 會依 grade_threshold 全部重算。
-- 不在此改用 grade_threshold 的理由：那張表的四榜門檻正是 V905 才寫入的，
-- 這裡 join 會 join 到空表，整批分數會靜默消失。
INSERT INTO product_score (id, product_id, weight_version_id, period, scene_type,
                           bonus_subtotal, penalty_subtotal,
                           final_score, grade, confidence, calculated_at)
SELECT m.score_id,
       m.product_id,
       2,
       -- ISO 週，如 2026W30
       to_char(CURRENT_DATE, 'IYYY"W"IW'),
       m.scene_type,
       agg.bonus,
       agg.penalty,
       GREATEST(0, agg.bonus - agg.penalty),
       CASE
           -- §5.6 硬規則：扣分達 20 以上者分級最高只給 B
           WHEN GREATEST(0, agg.bonus - agg.penalty) >= 85
                AND agg.penalty < 20                       THEN 'A'
           WHEN GREATEST(0, agg.bonus - agg.penalty) >= 70 THEN 'B'
           ELSE 'C'
       END,
       m.confidence,
       now() - interval '2 days'
FROM seed_score_meta m
         JOIN LATERAL (
             SELECT ROUND(COALESCE((SELECT SUM(e.normalized_value * e.effective_weight)
                                    FROM seed_effective_weight e
                                    WHERE e.product_id = m.product_id
                                      AND e.data_available), 0), 2)                AS bonus,
                    -- §5.5：扣分小計上限 40
                    LEAST(40, ROUND(COALESCE((SELECT SUM(p.penalty_value)
                                              FROM seed_penalty p
                                              WHERE p.product_id = m.product_id), 0), 2)) AS penalty
         ) agg ON TRUE;

-- 加分因子明細：weight 有值、penalty_value 為 NULL（對應 ck_score_factor_shape）
--
-- note 是 V17 依 §7.2.6 新增的欄位，畫面上直接顯示在該因子的長條旁邊。
-- 由 data_available／is_imputed 推出來，不另外手填，才不會與旗標對不上。
INSERT INTO score_factor (score_id, factor_code, raw_value, normalized_value, weight,
                          penalty_value, is_imputed, is_penalty, data_available, note)
SELECT m.score_id, e.factor_code, e.raw_value, e.normalized_value, e.effective_weight,
       NULL, e.is_imputed, FALSE, e.data_available,
       CASE
           WHEN NOT e.data_available THEN '無資料，權重已分攤給其餘因子'
           WHEN e.is_imputed         THEN '同品類樣本不足，以全品類基準計算'
       END
FROM seed_effective_weight e
         JOIN seed_score_meta m ON m.product_id = e.product_id;

-- 扣分因子明細：penalty_value 有值、weight 為 NULL
INSERT INTO score_factor (score_id, factor_code, raw_value, normalized_value, weight,
                          penalty_value, is_imputed, is_penalty, data_available, note)
SELECT m.score_id, p.factor_code, p.raw_value, NULL, NULL,
       p.penalty_value, FALSE, TRUE, TRUE, NULL
FROM seed_penalty p
         JOIN seed_score_meta m ON m.product_id = p.product_id;


-- ===================================================================
-- 歷史評分：FR-05 的分數走勢需要同一品項的多筆紀錄
-- ===================================================================
-- §5.10：每次評分產生新紀錄、不覆寫。這裡補上前兩週的分數，
-- 讓走勢圖不是一個孤點。
--
-- 歷史列刻意<b>不</b>附 score_factor 明細：實際系統當然會有，
-- 但為了讓這份 seed 保持可讀，僅保留當期的完整因子拆解。
-- 若前端的歷史頁需要因子明細，改用當期那一筆測試即可。

INSERT INTO product_score (id, product_id, weight_version_id, period, scene_type,
                           bonus_subtotal, penalty_subtotal,
                           final_score, grade, confidence, calculated_at)
-- id 也要自己給：當期那 17 筆是寫死 1–17 的，而 GENERATED BY DEFAULT AS IDENTITY
-- 的序列不會因為手動指定 id 而前進，這裡若讓資料庫自動配號會從 1 開始撞主鍵。
-- 歷史列一律從 100 起跳，序列的收尾統一在 V903 處理。
SELECT 100 + ROW_NUMBER() OVER (ORDER BY s.id, w.wk),
       s.product_id,
       -- v2 自 60 天前生效，因此 10 週前那一筆仍記在 v1 名下。
       -- AC-08-4 要的就是這件事：每筆評分都能回溯當時用的是哪一版權重。
       CASE WHEN w.wk = 1 THEN 2 ELSE 1 END,
       to_char(CURRENT_DATE - (w.wk * 7), 'IYYY"W"IW'),
       s.scene_type,
       adj.bonus, s.penalty_subtotal,
       GREATEST(0, adj.bonus - s.penalty_subtotal),
       CASE
           WHEN GREATEST(0, adj.bonus - s.penalty_subtotal) >= 85
                AND s.penalty_subtotal < 20 THEN 'A'
           WHEN GREATEST(0, adj.bonus - s.penalty_subtotal) >= 70 THEN 'B'
           ELSE 'C'
       END,
       s.confidence,
       now() - interval '2 days' - (w.wk * interval '7 days')
FROM product_score s
         CROSS JOIN (VALUES (1), (10)) AS w(wk)
         JOIN LATERAL (
             -- 往前推的分數略低，做出「近期上升」的走勢
             SELECT ROUND(GREATEST(0, LEAST(100, s.bonus_subtotal - w.wk * 1.4)), 2) AS bonus
         ) adj ON TRUE
WHERE s.id BETWEEN 1 AND 17;

DROP TABLE seed_effective_weight;
DROP TABLE seed_penalty;
DROP TABLE seed_bonus;
DROP TABLE seed_score_meta;


-- ===================================================================
-- 情境判定紀錄（規格書 §7.2 scene_classification_log）
-- ===================================================================
-- 三種情況都要有，否則 FR-11-3 的覆寫率指標永遠是 0：
--   1. AI 判定且採納
--   2. AI 判定但被人工覆寫（override_reason 必填）
--   3. AI 信心不足 → ai_scene_type 為 NULL，退回 REPLENISHMENT（§5.4）

-- V17 起本表多了三個欄位／改名：
--   period                CHAR(7) NOT NULL，覆寫率指標按期別統計要用
--   ai_alternative_scene  原 alternative_scene_type
--   ai_signals            原 signals，且放寬為可 NULL——AI 判定失敗時整組
--                         ai_* 欄位都沒有值，空陣列會讓「沒判定」與
--                         「判定結果是空的」分不出來

INSERT INTO scene_classification_log
    (product_id, period, ai_scene_type, ai_alternative_scene, ai_confidence, ai_reasoning,
     ai_signals, final_scene_type, overridden_by, override_reason, created_at) VALUES
  (101, to_char(CURRENT_DATE, 'IYYY"W"IW'), 'VIRAL', 'SEASONAL', 0.88,
   '近 7 日 Threads 討論量成長 340%，無明確節慶對應，歷史開團 2 次',
   '["heat_slope_7d +340%", "history_records 2", "festival_match none"]'::jsonb,
   'VIRAL', NULL, NULL, now() - interval '2 days'),

  (107, to_char(CURRENT_DATE, 'IYYY"W"IW'), 'SEASONAL', 'FESTIVAL', 0.71,
   '品項名稱含「中秋」，但熱度曲線呈現典型秋季上升形態，判定為季節導向',
   '["season_curve autumn", "festival_match MID_AUTUMN", "heat_slope_30d +64%"]'::jsonb,
   'FESTIVAL', 2,
   '蛋黃酥是中秋專屬品項，檔期一過需求歸零，屬節慶檔期型而非季節導向；季節導向會低估節慶時間窗的權重',
   now() - interval '2 days'),

  (113, to_char(CURRENT_DATE, 'IYYY"W"IW'), 'VIRAL', NULL, 0.83,
   '絲絨唇釉近 14 日於 Threads 與 IG 討論同步竄升，色號開箱貼文為主要驅動',
   '["heat_slope_7d +260%", "ig_category_signal high"]'::jsonb,
   'VIRAL', NULL, NULL, now() - interval '2 days'),

  -- 信心不足：§5.4 規定退回 REPLENISHMENT 並標示。
  -- ai_scene_type 與 ai_signals 一併為 NULL——沒有判定就是沒有判定。
  (114, to_char(CURRENT_DATE, 'IYYY"W"IW'), NULL, NULL, 0.62,
   '訊號分歧：熱度斜率為負但毛利率與同類表現落差大，無法明確歸類。信心值低於門檻 0.7',
   NULL,
   'REPLENISHMENT', NULL, NULL, now() - interval '2 days'),

  (117, to_char(CURRENT_DATE, 'IYYY"W"IW'), 'VIRAL', 'REPLENISHMENT', 0.79,
   '由 B 軌尋源成案轉入，探索期間熱度持續上升且尚未進入高原期',
   '["heat_slope_7d +96%", "stage RISING", "stage_weeks 3"]'::jsonb,
   'VIRAL', NULL, NULL, now() - interval '2 days'),

  (104, to_char(CURRENT_DATE - 7, 'IYYY"W"IW'), 'REPLENISHMENT', 'SEASONAL', 0.75,
   '需求穩定、重複開團 3 次，無明顯社群竄升訊號',
   '["history_records 3", "heat_slope_7d +4%"]'::jsonb,
   'SEASONAL', 1,
   '冷凍甜點在夏季需求明顯高於冬季，過去兩年夏季開團量為冬季的 2.4 倍，應以季節導向計算',
   now() - interval '9 days'),

  (102, to_char(CURRENT_DATE - 21, 'IYYY"W"IW'), 'VIRAL', 'SEASONAL', 0.68,
   '近 7 日討論量成長 124%，判定為話題竄升',
   '["heat_slope_7d +124%", "last_year_same_period matched"]'::jsonb,
   'SEASONAL', 6,
   '草莓年糕是每年冬季固定回歸的品項，去年同期熱度曲線幾乎一致，這波成長是季節性而非話題性；'
   || '用話題爆款型會把 55% 權重壓在熱度斜率上，嚴重高估',
   now() - interval '20 days');


-- ===================================================================
-- AI 洞察（規格書 §7.2 ai_insight、FR-05 區塊 F）
-- ===================================================================
-- content_json 的結構直接對應 §6.3 各 Agent 的輸出格式。
-- cost_usd 全為 0：§3.2 模型策略是一律使用免費模型（:free）。
-- 同 product + type 只有一筆 is_current = TRUE，這裡也放了一筆歷史版本，
-- 讓「換 prompt 版本後比較輸出」的情境有資料可比。

-- V17 起多了三個欄位：model_alias（邏輯別名）、request_count（配額的主要度量，
-- §7.2.7 明訂 cost_usd 降為次要）、from_cache（命中快取不計入配額）。
-- request_count 刻意不是全部 1：104 那兩筆是重試過一次的，
-- 全填 1 的話「請求數」這個度量在畫面上永遠等於筆數，看不出它的用途。

INSERT INTO ai_insight (product_id, insight_type, content_json, model, model_alias, prompt_version,
                        source_review_count, prompt_tokens, completion_tokens,
                        request_count, cost_usd, from_cache,
                        generated_at, is_current) VALUES
  (101, 'SELLING_POINT',
   '{"points":[{"title":"抹茶風味層次分明","evidence":"37 則評論提及茶香不苦澀","confidence":0.86},{"title":"夾心比例討喜","evidence":"22 則評論提及內餡份量足","confidence":0.78},{"title":"包裝適合送禮","evidence":"15 則評論提及外盒質感","confidence":0.64}],"summary":"風味與包裝是兩條可用的行銷主軸"}'::jsonb,
   'meta-llama/llama-3.3-70b-instruct:free', 'PRIMARY_FREE', 'sp-v1.2', 48, 2140, 386, 1, 0, FALSE, now() - interval '2 days', TRUE),

  (101, 'RISK',
   '{"themes":[{"theme":"物流破損","count":4,"ratio":0.083,"severity":"LOW","samples":["外盒有壓痕但餅乾完好"]},{"theme":"品質","count":2,"ratio":0.042,"severity":"LOW","samples":[]}],"foodSafetyFlag":false,"negativeRatio":0.104,"categoryThreshold":0.15,"verdict":"未達零食類負評門檻 15%，不觸發評論風險扣分"}'::jsonb,
   'meta-llama/llama-3.3-70b-instruct:free', 'PRIMARY_FREE', 'rr-v2.0', 48, 3020, 512, 1, 0, FALSE, now() - interval '2 days', TRUE),

  (101, 'RECOMMENDATION',
   '{"suggestion":"ADOPT","firstOrderQty":600,"reasoning":"熱度斜率位居同品類前 4%，毛利率與轉換率皆在中上，物流風險僅夏季融化一項且可用保冷包裝緩解","cautions":["建議於 9 月後開團以避開高溫","保冷包裝成本約每單 8 元，需納入毛利估算"]}'::jsonb,
   'meta-llama/llama-3.3-70b-instruct:free', 'PRIMARY_FREE', 'rec-v1.4', 48, 2860, 448, 1, 0, FALSE, now() - interval '2 days', TRUE),

  -- 歷史版本：同 product + type，is_current = FALSE
  (101, 'SELLING_POINT',
   '{"points":[{"title":"抹茶口味","evidence":"多數評論正面","confidence":0.55}],"summary":"評論整體正面"}'::jsonb,
   'mistralai/mistral-7b-instruct:free', 'FALLBACK_FREE', 'sp-v1.0', 31, 1420, 190, 1, 0, FALSE, now() - interval '35 days', FALSE),

  (104, 'RISK',
   '{"themes":[{"theme":"物流破損","count":18,"ratio":0.145,"severity":"HIGH","samples":["送到已經退冰變形","外包裝濕透"]},{"theme":"品質","count":9,"ratio":0.073,"severity":"MEDIUM","samples":["口感和店裡吃的差很多"]}],"foodSafetyFlag":false,"negativeRatio":0.22,"categoryThreshold":0.15,"verdict":"負評率 22% 超過生鮮冷凍類門檻 15%，且集中於物流破損，觸發評論風險扣分"}'::jsonb,
   'meta-llama/llama-3.3-70b-instruct:free', 'PRIMARY_FREE', 'rr-v2.0', 124, 4820, 690, 2, 0, FALSE, now() - interval '2 days', TRUE),

  (104, 'RECOMMENDATION',
   '{"suggestion":"WATCH","firstOrderQty":null,"reasoning":"分數本身不差，但冷鏈物流的負評集中且比例高，先解決配送問題再談開團","cautions":["需確認冷鏈業者的到貨溫層紀錄","建議改為限定區域配送以縮短運送時間"]}'::jsonb,
   'meta-llama/llama-3.3-70b-instruct:free', 'PRIMARY_FREE', 'rec-v1.4', 124, 3980, 520, 2, 0, FALSE, now() - interval '2 days', TRUE),

  (107, 'TREND',
   '{"stage":"RISING","stageWeeks":2,"estimatedLifespanDays":56,"interpretation":"中秋前 60 天為蛋黃酥的典型爬升期，今年起漲時間較去年提早約 9 天","signals":["heat_slope_7d +182%","heat_slope_30d +64%"],"peakWarning":false}'::jsonb,
   'meta-llama/llama-3.3-70b-instruct:free', 'PRIMARY_FREE', 'ti-v1.1', 0, 1980, 342, 1, 0, FALSE, now() - interval '2 days', TRUE),

  (113, 'SELLING_POINT',
   '{"points":[{"title":"顯色度高且不乾","evidence":"29 則評論同時提到顯色與保濕","confidence":0.81},{"title":"色號齊全","evidence":"18 則評論提及選色多","confidence":0.70}],"summary":"顯色與保濕的組合是主要差異點"}'::jsonb,
   'meta-llama/llama-3.3-70b-instruct:free', 'PRIMARY_FREE', 'sp-v1.2', 34, 1760, 298, 1, 0, FALSE, now() - interval '2 days', TRUE),

  (117, 'TREND',
   '{"stage":"RISING","stageWeeks":3,"estimatedLifespanDays":56,"interpretation":"健康零食話題帶動，上升期已持續三週且斜率未見趨緩","signals":["heat_slope_7d +96%","heat_slope_30d +210%"],"peakWarning":false}'::jsonb,
   'meta-llama/llama-3.3-70b-instruct:free', 'PRIMARY_FREE', 'ti-v1.1', 0, 1640, 286, 0, 0, TRUE, now() - interval '4 days', TRUE);


-- ===================================================================
-- 風險示警（規格書 §7.2 risk_alert、FR-10）
-- ===================================================================
-- AC-10-4：扣分達 20 分以上者必定出現於本清單。
-- 目前符合的是品項 104（扣 24）與 116（扣 20），兩者都有對應列。
-- 另外補上 FR-10-1 的其他示警類型。三種處理狀態都有，
-- AC-10-2 的「已忽略不進預設清單」才測得到。
--
-- risk_type 自 V17 起是九值列舉（§7.2.8），本檔用到其中七個。
-- 兩個舊值已隨 V17 消失：
--   TOTAL_PENALTY    → PENALTY_CAP（同一件事，v3.0 的命名）
--   HEAT_PLUNGE      → HEAT_CRASH（同上）
--   SUPPLIER_ANOMALY → 沒有對應值。§7.2.2 明訂「供應商異常」示警已移出本階段
--                      範圍：沒有任何資料源提供交期、到貨率或退件資訊，
--                      supplier 表也沒有可判定「異常」的欄位。
--                      原本那一列改為 LOW_CONFIDENCE，掛在信心值 44 的品項 114 上。

INSERT INTO risk_alert (product_id, risk_type, severity, trigger_value, status,
                        ignore_reason, detected_at, handled_at, handled_by) VALUES
  (104, 'REVIEW_RISK',    'HIGH',   '負評率 22%（生鮮冷凍類門檻 15%），集中於物流破損', 'OPEN',         NULL, now() - interval '2 days', NULL, NULL),
  (104, 'LOGISTICS_RISK', 'HIGH',   '冷凍需冷鏈，扣分達上限 10 分',                      'ACKNOWLEDGED', NULL, now() - interval '2 days', now() - interval '1 days', 1),
  (104, 'PENALTY_CAP',    'HIGH',   '扣分小計 24 分，達強制列入門檻 20 分',              'OPEN',         NULL, now() - interval '2 days', NULL, NULL),
  (116, 'REVIEW_RISK',    'MEDIUM', '負評率 18%（零食類門檻 15%）',                      'ACKNOWLEDGED', NULL, now() - interval '9 days', now() - interval '8 days', 2),
  (116, 'PENALTY_CAP',    'MEDIUM', '扣分小計 20 分，達強制列入門檻 20 分',              'ACKNOWLEDGED', NULL, now() - interval '9 days', now() - interval '8 days', 2),
  (103, 'LOGISTICS_RISK', 'MEDIUM', '冷藏需冷鏈，且效期僅 45 天',                        'OPEN',         NULL, now() - interval '3 days', NULL, NULL),
  (107, 'INVENTORY_RISK', 'MEDIUM', '效期 30 天，且為節慶專屬品項',                      'OPEN',         NULL, now() - interval '2 days', NULL, NULL),
  (114, 'HEAT_CRASH',     'LOW',    '7 日熱度斜率 −21%，30 日斜率 −38%',                 'IGNORED',
   '氣炸鍋本來就是去年的品項，這一波下滑符合預期，不需要處理', now() - interval '5 days', now() - interval '4 days', 1),
  (102, 'SEASON_MISMATCH', 'LOW',   '品項標記為 WINTER，但目前為備貨期外的月份',         'IGNORED',
   '這批是為了下一季提前備貨，時間點是刻意的', now() - interval '6 days', now() - interval '6 days', 2),
  (114, 'LOW_CONFIDENCE', 'LOW',    '評分信心 44，低於門檻 60；情境判定亦退回常態補貨型', 'OPEN',        NULL, now() - interval '2 days', NULL, NULL);
