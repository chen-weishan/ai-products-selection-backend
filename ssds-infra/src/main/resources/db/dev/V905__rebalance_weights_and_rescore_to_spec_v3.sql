-- ===================================================================
-- V905 依 v3.0 重算開發用假資料：六因子權重、四榜門檻、分數與信心度
-- ===================================================================
--
-- V13 對齊的是 schema，本檔對齊的是「資料」。V902／V904 的假資料是照
-- v2.0 算出來的，v3.0 有三處把它們算錯了：
--
--   1. §FR-08 的加權因子由五個改為六個：新增 PRICE_FIT，
--      並把 HEAT_VOLUME 從加權因子降級為門檻條件（§5.2.1-a）。
--      V902 三個權重版本都沒有 PRICE_FIT，v3 草稿還把權重分給了 HEAT_VOLUME。
--   2. §5.5 取消二次正規化。V904 對 bonus_subtotal 再做了一次同品類百分位，
--      v3.0 明文裁決「加分小計 = Σ(w_i × normalized_i)，不做二次換算」——
--      加權和本身已是百分位的加權平均，再換算一次沒有增加資訊。
--      目前資料庫裡的 bonus_subtotal 全是 n/23×100 這種離散值，即為此故。
--   3. §5.3.1 的退路由兩段改為三段：≥10 同品類／3–9 合併兄弟品類／
--      <3 全品類。V904 用的是 v2.0 的兩段。
--
-- 【本檔沒有推翻 §5.3.1】
-- 同品類百分位仍然有效，只是作用層級被釐清：它作用在
-- score_factor.normalized_value（單一因子的正規化），不是 bonus_subtotal。
-- 因此 V904 為了讓「零食」湊滿 10 筆而補的六個品項（id 130–135）仍有意義，
-- 沒有它們，§5.3.1 的第一段（≥ 10）一次都不會被執行到。
--
-- 【percentile_rank 仍採 CUME_DIST】
-- 沿用 V904 的定義：小於等於該值的比例（rank / n），不是 SQL 的
-- PERCENT_RANK（(rank−1)/(n−1)，最低者恆為 0）。本檔改用相關子查詢
-- 明寫這個定義，因為三段退路的比較母體會跨品類，窗框函式的 PARTITION
-- 表達不了「這一列要跟哪一群比」。
--
-- 【執行順序】
-- 檔名編號 905 > 13，在乾淨資料庫上排在 V13 之後，順序正確。
-- 本檔假設 V13 已套用（需要 grade_threshold 與新情境代碼）。
-- ===================================================================


-- ===================================================================
-- 1. weight_profile 全部重建為 §FR-08 的六因子
-- ===================================================================
-- 三個版本一起重建。保留 v1／v3 與 v2 的差異，是為了讓 AC-08-4
-- 「每筆評分可回溯當時用的權重版本」在畫面上看得出差別；若三版數值
-- 相同，版本比較頁就沒有東西可比。
--
-- 三版都不再出現 HEAT_VOLUME：v3.0 §5.2.1-a 已把它降級為門檻條件
-- （擋「3 則變 9 則」的小基數暴衝），門檻條件不參與加權。
-- weight_profile 的 CHECK 已於 V17 收斂為 v3.0 的六個加分因子，
-- HEAT_VOLUME 現在連寫都寫不進去，本檔不需要再處理它。

-- 只刪本檔負責重建的三個假資料版本。
--
-- 不用不帶條件的 DELETE：本檔套用時共用資料庫上可能已經多出第四個
-- weight_version（誰都能透過 FR-08 的畫面建立草稿版本）。全表刪除會
-- 把那個版本的權重一併清掉而本檔又不會重建，留下一個「有版本、沒權重」
-- 的空殼。第 11 段的加總驗證抓不到這種情況——沒有列就沒有分組，
-- HAVING 自然不會命中，migration 會安靜地套用成功。
DELETE FROM weight_profile WHERE version_id IN (1, 2, 3);

-- 若真的存在其他版本，出個聲。不 RAISE EXCEPTION 是因為那些版本是別人
-- 的資料，本檔沒有立場擋下整組人的啟動；但套用者需要知道它們沒被重算，
-- 其權重仍是舊的五因子、缺 PRICE_FIT。
DO $$
DECLARE
    others TEXT;
BEGIN
    SELECT string_agg(id::TEXT, ', ' ORDER BY id) INTO others
    FROM weight_version WHERE id NOT IN (1, 2, 3);

    IF others IS NOT NULL THEN
        RAISE NOTICE 'V905 未處理的 weight_version：%。'
                     '本檔只重建假資料的 v1/v2/v3，這些版本的權重維持原狀，'
                     '若仍是舊的五因子則不符 §FR-08，需另行處理。', others;
    END IF;
END $$;

-- v1（RETIRED）：v2.0 初版的五因子值，從各組最大的因子讓出 0.07 給
-- PRICE_FIT。這是「舊版加上新因子」該有的樣子，不是規格建議值。
INSERT INTO weight_profile (version_id, scene_type, factor_code, weight) VALUES
  (1, 'VIRAL',         'TREND', 0.480), (1, 'VIRAL',         'MARGIN',     0.100),
  (1, 'VIRAL',         'CVR', 0.100), (1, 'VIRAL',         'PRICE_FIT',  0.070),
  (1, 'VIRAL',         'FESTIVAL',   0.150), (1, 'VIRAL',         'CLIMATE',    0.100),

  (1, 'FESTIVAL',      'TREND', 0.200), (1, 'FESTIVAL',      'MARGIN',     0.200),
  (1, 'FESTIVAL',      'CVR', 0.200), (1, 'FESTIVAL',      'PRICE_FIT',  0.070),
  (1, 'FESTIVAL',      'FESTIVAL',   0.230), (1, 'FESTIVAL',      'CLIMATE',    0.100),

  (1, 'REPLENISHMENT', 'TREND', 0.150), (1, 'REPLENISHMENT', 'MARGIN',     0.280),
  (1, 'REPLENISHMENT', 'CVR', 0.350), (1, 'REPLENISHMENT', 'PRICE_FIT',  0.070),
  (1, 'REPLENISHMENT', 'FESTIVAL',   0.050), (1, 'REPLENISHMENT', 'CLIMATE',    0.100),

  (1, 'SEASONAL',      'TREND', 0.200), (1, 'SEASONAL',      'MARGIN',     0.250),
  (1, 'SEASONAL',      'CVR', 0.200), (1, 'SEASONAL',      'PRICE_FIT',  0.070),
  (1, 'SEASONAL',      'FESTIVAL',   0.050), (1, 'SEASONAL',      'CLIMATE',    0.230);

-- v2（ACTIVE）：v3.0 §FR-08 的四組建議值，逐格照抄。
--
--   權重組       熱度斜率  毛利率  轉換率  價格帶  節慶窗  氣候
--   話題爆款型     50%     10%     8%      7%     15%    10%
--   節慶檔期型     18%     18%    17%      7%     30%    10%
--   常態補貨型     13%     30%    30%     12%      5%    10%
--   季節導向型     18%     22%    18%      7%      5%    30%
--
-- AC-08-1：每組加總須為 1.000，上表四列皆成立。
INSERT INTO weight_profile (version_id, scene_type, factor_code, weight) VALUES
  (2, 'VIRAL',         'TREND', 0.500), (2, 'VIRAL',         'MARGIN',     0.100),
  (2, 'VIRAL',         'CVR', 0.080), (2, 'VIRAL',         'PRICE_FIT',  0.070),
  (2, 'VIRAL',         'FESTIVAL',   0.150), (2, 'VIRAL',         'CLIMATE',    0.100),

  (2, 'FESTIVAL',      'TREND', 0.180), (2, 'FESTIVAL',      'MARGIN',     0.180),
  (2, 'FESTIVAL',      'CVR', 0.170), (2, 'FESTIVAL',      'PRICE_FIT',  0.070),
  (2, 'FESTIVAL',      'FESTIVAL',   0.300), (2, 'FESTIVAL',      'CLIMATE',    0.100),

  (2, 'REPLENISHMENT', 'TREND', 0.130), (2, 'REPLENISHMENT', 'MARGIN',     0.300),
  (2, 'REPLENISHMENT', 'CVR', 0.300), (2, 'REPLENISHMENT', 'PRICE_FIT',  0.120),
  (2, 'REPLENISHMENT', 'FESTIVAL',   0.050), (2, 'REPLENISHMENT', 'CLIMATE',    0.100),

  (2, 'SEASONAL',      'TREND', 0.180), (2, 'SEASONAL',      'MARGIN',     0.220),
  (2, 'SEASONAL',      'CVR', 0.180), (2, 'SEASONAL',      'PRICE_FIT',  0.070),
  (2, 'SEASONAL',      'FESTIVAL',   0.050), (2, 'SEASONAL',      'CLIMATE',    0.300);

-- v3（DRAFT，FR-15 校準產出）：原本的故事是「把熱度斜率的一部分讓給
-- 熱度絕對量級」，v3.0 把 HEAT_VOLUME 降級後這個故事不成立了。
-- 改為「價格帶適配度的預測力被低估，建議調高，由熱度斜率讓出」——
-- 每組 TREND −0.03、PRICE_FIT +0.03，加總維持 1.000。
-- 第 10 段會把 V903 的校準報告內文一併改成同一套說法。
INSERT INTO weight_profile (version_id, scene_type, factor_code, weight) VALUES
  (3, 'VIRAL',         'TREND', 0.470), (3, 'VIRAL',         'MARGIN',     0.100),
  (3, 'VIRAL',         'CVR', 0.080), (3, 'VIRAL',         'PRICE_FIT',  0.100),
  (3, 'VIRAL',         'FESTIVAL',   0.150), (3, 'VIRAL',         'CLIMATE',    0.100),

  (3, 'FESTIVAL',      'TREND', 0.150), (3, 'FESTIVAL',      'MARGIN',     0.180),
  (3, 'FESTIVAL',      'CVR', 0.170), (3, 'FESTIVAL',      'PRICE_FIT',  0.100),
  (3, 'FESTIVAL',      'FESTIVAL',   0.300), (3, 'FESTIVAL',      'CLIMATE',    0.100),

  (3, 'REPLENISHMENT', 'TREND', 0.100), (3, 'REPLENISHMENT', 'MARGIN',     0.300),
  (3, 'REPLENISHMENT', 'CVR', 0.300), (3, 'REPLENISHMENT', 'PRICE_FIT',  0.150),
  (3, 'REPLENISHMENT', 'FESTIVAL',   0.050), (3, 'REPLENISHMENT', 'CLIMATE',    0.100),

  (3, 'SEASONAL',      'TREND', 0.150), (3, 'SEASONAL',      'MARGIN',     0.220),
  (3, 'SEASONAL',      'CVR', 0.180), (3, 'SEASONAL',      'PRICE_FIT',  0.100),
  (3, 'SEASONAL',      'FESTIVAL',   0.050), (3, 'SEASONAL',      'CLIMATE',    0.300);


-- ===================================================================
-- 2. grade_threshold 改為 §FR-08 的四榜門檻
-- ===================================================================
-- V13 建表時刻意沿用各版本原本的純量門檻（85/70），理由是「既有
-- product_score 的 grade 是用舊門檻算的，改門檻會讓歷史分級重現不出來」。
--
-- 那個理由在真實資料上成立，在這份假資料上不成立：本檔第 6、7 段
-- 會把每一筆 grade 全部重算，舊分級不是需要保存的稽核紀錄，
-- 而是照錯規格算出來的值。三個版本一併改為 §FR-08 建議值。
--
-- 【為什麼是 INSERT ... ON CONFLICT 而不是 UPDATE】
-- 乾淨資料庫上 grade_threshold 是空的：V13 的版本號 13 小於 V900，
-- 執行時 weight_version 還沒有任何列，那支 CROSS JOIN 回填不出東西。
-- 共用資料庫則相反 —— V900–V904 早就套用完，V13 會回填出 12 列。
-- 兩種情境都要能跑，所以用 upsert，不能只 UPDATE
-- （只 UPDATE 的話乾淨資料庫上第 6、7 段會 JOIN 不到列而整段靜默失效）。

--
-- 【為什麼限定 v1/v2/v3】與第 1 段同一個理由。套用當下若已存在第四個
-- weight_version，它的四榜門檻是別人設的；不限定的話這支 upsert 會把
-- 它靜默改寫成 §FR-08 的建議值。第 1 段保住了那個版本的權重，
-- 門檻也必須一起保住，否則權重是舊的、門檻是新的，比全被覆寫更難查。
-- 那個版本的門檻由 V13 依其自身的純量值回填，本檔不動。

INSERT INTO grade_threshold (version_id, scene_type, grade_a_min, grade_b_min)
SELECT v.id, t.scene_type, t.a, t.b
FROM weight_version v
         CROSS JOIN (VALUES
             ('VIRAL',         85.00, 70.00),
             ('FESTIVAL',      85.00, 70.00),
             ('REPLENISHMENT', 80.00, 65.00),
             ('SEASONAL',      82.00, 68.00)
         ) AS t(scene_type, a, b)
WHERE v.id IN (1, 2, 3)
ON CONFLICT (version_id, scene_type)
DO UPDATE SET grade_a_min = EXCLUDED.grade_a_min,
              grade_b_min = EXCLUDED.grade_b_min;


-- ===================================================================
-- 3. 建立本次重算的工作集與 §5.3.1 三段分派
-- ===================================================================
-- 只有當期評分帶因子明細，重算因子的範圍就是當期這 23 筆
-- （V902 的 17 筆 + V904 補的 6 筆）。歷史列在第 7 段另外處理。
--
-- 【「當期」取自資料，不取自 CURRENT_DATE】
-- V902／V904 用 to_char(CURRENT_DATE, 'IYYY"W"IW') 產生 period，那些值
-- 在它們套用的那一天就被釘死了（共用資料庫上是 2026W34）。本檔若也用
-- CURRENT_DATE 判斷當期，只要晚一週套用就會選出 0 列 —— 後續每一段
-- 都 JOIN 不到東西，整個重算靜默跳過，而 Flyway 仍記為 success。
-- 改以 product_score 裡最大的 period 為當期：ISO 週字串補零對齊，
-- 字典序等同時間序。
--
-- 【工作集以固定的 score id 界定，不用 period 推測】
--
-- 共用資料庫是全組共用的，組員的 Agent 隨時可能寫進新的 product_score。
-- 本檔早先兩版都試圖從資料「推測」哪些列屬於假資料：先用 CURRENT_DATE，
-- 再改用「有因子明細的最大 period」。兩者都還是推測，都能被繞過——
-- 只要有人在套用前寫入一筆較新 period 且帶完整 score_factor 的正常評分，
-- 它就會被當成當期，該重算的 23 筆假資料反而被排除，而且工作集非空、
-- 第 3 段的守門也不會觸發。
--
-- 改為直接列出 V902／V904 寫死的 score id。那些 id 是 migration 檔案裡的
-- 常數，不隨資料變動，也就沒有被綁架的餘地：
--
--   1–17     V902 的當期評分（品項 101–117）
--   101–134  V902 的歷史評分（每筆當期各往前 1 週與 10 週，共 34 筆）
--   200–205  V904 為補足零食樣本數而加的評分（品項 130–135）
--
-- 範圍寫死到端點而不是取整數區間（如 100–299），是因為 V903 的序列收尾
-- 把 product_score 的 identity 停在 206：假資料的最大 id 就是 205，
-- 之後任何人寫入的評分一律從 207 起跳。序列只增不減，所以上面三段區間
-- 永遠只會命中假資料。若寫成 100–299，組員的第一筆新評分（207）
-- 就會被當成假資料而被重算。
--
-- 界定之後，「當期」與「歷史」不再靠 period 區分，而是靠有沒有因子明細：
-- V902 刻意只給當期列 score_factor，歷史列沒有。

-- is_current 直接由 id 決定，不再用「有沒有 score_factor」判斷：
-- 那個判準會在歷史列日後被補上因子、或當期列缺因子時把資料分錯邊。
-- id 是 migration 檔案裡的常數，不會因為資料變動而改變分類。
CREATE TEMP TABLE v905_seed AS
SELECT id AS score_id,
       (id BETWEEN 1 AND 17 OR id BETWEEN 200 AND 205) AS is_current
FROM product_score
WHERE id BETWEEN 1 AND 17
   OR id BETWEEN 101 AND 134
   OR id BETWEEN 200 AND 205;

CREATE TEMP TABLE v905_cur AS
SELECT s.id AS score_id,
       s.product_id,
       s.weight_version_id,
       s.scene_type,
       p.category_id,
       c.parent_id,
       p.suggested_price
FROM product_score s
         JOIN v905_seed sd ON sd.score_id = s.id AND sd.is_current
         JOIN product   p  ON p.id = s.product_id
         JOIN category  c  ON c.id = p.category_id;

-- 靜默失效的防線：不是檢查「有沒有列」，而是檢查「筆數對不對」。
-- 只驗非空的話，23 筆當期只剩 1 筆也會通過，正是本檔一路在修的
-- 「該算的沒算，驗證卻通過」。筆數由 V902／V904 決定，是固定值。
DO $$
DECLARE
    n_seed INT;
    n_cur  INT;
    n_hist INT;
BEGIN
    SELECT COUNT(*) INTO n_seed FROM v905_seed;
    SELECT COUNT(*) INTO n_cur  FROM v905_cur;
    SELECT COUNT(*) INTO n_hist FROM v905_seed WHERE NOT is_current;

    IF n_seed <> 57 OR n_cur <> 23 OR n_hist <> 34 THEN
        RAISE EXCEPTION
            'V905 中止：假資料工作集不完整（seed=% 應為 57、當期=% 應為 23、歷史=% 應為 34）。'
            '假資料被刪改過，重算範圍不可信，請先確認 V902／V904 的資料完整',
            n_seed, n_cur, n_hist;
    END IF;
END $$;

-- §5.3.1 三段退路。判斷依據是「自己品類的已評分筆數」：
--
--   >= 10       → CATEGORY：同品類百分位，信心度不扣
--   3 – 9       → SIBLING ：與同父品類的兄弟品類合併計算，扣 20
--   < 3 或合併後仍不足 → OVERALL：全品類百分位，扣 20，UI 須標示
--
-- 注意第二段的判斷不是「自己 3–9 筆」而是「合併後 >= 3 筆」：
-- 保養品 2 筆、彩妝 1 筆各自都不足 3，合併為美妝後恰好 3 筆，
-- 依規格應走合併段而非直接退到全品類。
--
-- 目前的品類分布讓三段都走得到：
--   零食 10                        → CATEGORY
--   飲品 3／生鮮冷凍 3（食品共 16） → SIBLING
--   保養品 2／彩妝 1（美妝共 3）    → SIBLING
--   清潔用品 1／紙製品 1（日用品 2）→ OVERALL
--   小家電 2（家電共 2）            → OVERALL
CREATE TEMP TABLE v905_tier AS
SELECT cur.score_id,
       cur.product_id,
       cur.suggested_price,
       CASE WHEN cn.n >= 10 THEN 'CATEGORY'
            WHEN pn.n >= 3  THEN 'SIBLING'
            ELSE 'OVERALL' END AS tier,
       -- 價格帶中位數要取自哪一群（第 4 段用）。OVERALL 不取，見該段說明。
       CASE WHEN cn.n >= 10 THEN 'C:' || cur.category_id
            WHEN pn.n >= 3  THEN 'P:' || cur.parent_id END AS band_key
FROM v905_cur cur
         JOIN (SELECT category_id, COUNT(*) AS n FROM v905_cur GROUP BY category_id) cn
              ON cn.category_id = cur.category_id
         LEFT JOIN (SELECT parent_id, COUNT(*) AS n FROM v905_cur
                    WHERE parent_id IS NOT NULL GROUP BY parent_id) pn
              ON pn.parent_id = cur.parent_id;


-- ===================================================================
-- 4. 補上 PRICE_FIT 因子（§FR-08 六因子的第六個）
-- ===================================================================
-- score_factor 完全沒有 PRICE_FIT 列，權重補了也沒有東西可乘。
--
-- 【raw_value 的定義】
-- 價格帶適配度 = max(0, 1 − |建議售價 ÷ 同群中位售價 − 1|)，值域 [0, 1]。
-- 售價等於中位數時為 1，偏離一倍（兩倍價或免費）時為 0。
-- 這是本檔為假資料選的可計算定義，不是規格給的公式；
-- 應用層實作 PRICE_FIT 時以規格為準，不要拿這裡的式子當依據。
--
-- 【為什麼 OVERALL 段標為無資料】
-- 中位售價取自與 §5.3.1 相同的比較群。落到 OVERALL 段代表連合併兄弟
-- 品類都湊不到 3 筆，此時的「中位售價」會是跨品類的（衛生紙跟氣炸鍋
-- 比價），沒有意義。標為 data_available = FALSE：UI 顯示灰底、
-- 不扣分、權重依 §5.7 分攤給其餘五個因子——這正是規格要的行為，
-- 也順便讓灰底長條在畫面上有實例可看。

CREATE TEMP TABLE v905_price_band AS
SELECT 'C:' || category_id AS band_key,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY suggested_price)::numeric AS median_price
FROM v905_cur
GROUP BY category_id
UNION ALL
SELECT 'P:' || parent_id,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY suggested_price)::numeric
FROM v905_cur
WHERE parent_id IS NOT NULL
GROUP BY parent_id;

INSERT INTO score_factor (score_id, factor_code, raw_value, normalized_value, weight,
                          penalty_value, is_imputed, is_penalty, data_available)
SELECT t.score_id,
       'PRICE_FIT',
       CASE WHEN t.tier <> 'OVERALL'
            THEN ROUND(GREATEST(0::numeric,
                                1 - ABS(t.suggested_price / b.median_price - 1)), 4) END,
       NULL,                      -- normalized_value 在第 5 段統一算
       NULL,                      -- weight 在第 5 段統一算
       NULL, FALSE, FALSE,
       t.tier <> 'OVERALL'
FROM v905_tier t
         LEFT JOIN v905_price_band b ON b.band_key = t.band_key
WHERE NOT EXISTS (SELECT 1 FROM score_factor f
                  WHERE f.score_id = t.score_id AND f.factor_code = 'PRICE_FIT');


-- ===================================================================
-- 5. 依 §5.3.1 三段退路重算 normalized_value 與 §5.7 有效權重
-- ===================================================================

-- 防禦：標為有資料卻沒有原始值的加分因子算不出百分位。
-- 目前沒有這種列，但若日後有人補資料補一半，這裡會把它歸為無資料，
-- 而不是讓它帶著上一輪的 normalized_value 混進加權和。
UPDATE score_factor f
SET data_available   = FALSE,
    normalized_value = NULL
FROM v905_tier t
WHERE t.score_id = f.score_id
  AND NOT f.is_penalty
  AND f.data_available
  AND f.raw_value IS NULL;

-- percentile_rank(x) = 比較群中「<= x」的比例 × 100，即 CUME_DIST 的定義。
-- 這裡用相關子查詢而非窗框函式：比較群會跨品類（SIBLING 段要把零食、
-- 飲品、生鮮冷凍算在一起，但零食自己走 CATEGORY 段），
-- 同一列可能同時屬於多個群，PARTITION BY 表達不了這件事。
UPDATE score_factor f
SET normalized_value = r.pct
FROM (
    SELECT f2.id,
           ROUND(100.0 * pr.le / NULLIF(pr.total, 0), 2) AS pct
    FROM score_factor f2
             JOIN v905_tier t   ON t.score_id = f2.score_id
             JOIN v905_cur  cur ON cur.score_id = f2.score_id
             JOIN LATERAL (
                 SELECT COUNT(*) FILTER (WHERE pf.raw_value <= f2.raw_value) AS le,
                        COUNT(*)                                             AS total
                 FROM score_factor pf
                          JOIN v905_cur pc ON pc.score_id = pf.score_id
                 WHERE pf.factor_code = f2.factor_code
                   AND NOT pf.is_penalty
                   AND pf.data_available
                   AND pf.raw_value IS NOT NULL
                   AND (t.tier = 'OVERALL'
                        OR (t.tier = 'CATEGORY' AND pc.category_id = cur.category_id)
                        OR (t.tier = 'SIBLING'  AND pc.parent_id   = cur.parent_id))
             ) pr ON TRUE
    WHERE NOT f2.is_penalty
      AND f2.data_available
      AND f2.raw_value IS NOT NULL
) r
WHERE f.id = r.id;

-- §5.7 有效權重：原始權重 ÷ 有資料因子的權重總和。
-- 六個因子都有資料時分母為 1，有效權重等於原始權重。
-- 無資料的因子 weight 設為 NULL（不是 0）：0 會讓 UI 畫出一根長度為零
-- 的長條，NULL 才是「這個因子這次沒有參與」。
UPDATE score_factor f
SET weight = w.effective_weight
FROM (
    SELECT f2.id,
           CASE WHEN f2.data_available
                THEN ROUND(wp.weight
                           / NULLIF(SUM(CASE WHEN f2.data_available THEN wp.weight ELSE 0 END)
                                        OVER (PARTITION BY f2.score_id), 0), 3)
           END AS effective_weight
    FROM score_factor f2
             -- 限定工作集：不加這道，任何品項只要版本與情境對得上
             -- weight_profile 就會被改寫有效權重，包含組員新建的評分
             JOIN v905_cur cur ON cur.score_id = f2.score_id
             JOIN product_score s ON s.id = f2.score_id
             JOIN weight_profile wp ON wp.version_id  = s.weight_version_id
                                   AND wp.scene_type  = s.scene_type
                                   AND wp.factor_code = f2.factor_code
    WHERE NOT f2.is_penalty
) w
WHERE f.id = w.id;

DROP TABLE v905_price_band;


-- ===================================================================
-- 6. 依 §5.5 重算加分小計、選品分數與分級（當期）
-- ===================================================================
-- 加分小計 = Σ(w'_i × normalized_i)，直接落在 [0, 100]，不做二次換算。
-- 這是本檔存在的主要理由：V904 在這裡多做了一次同品類百分位。
--
-- base_score／risk_penalty 兩個 v1.0 欄位已於 V17 移除（與 bonus_subtotal／
-- penalty_subtotal 語意重複），所以這裡只更新後兩者。
-- 扣分本身不動：v3.0 沒有改 §5.2.2。

WITH agg AS (
    SELECT f.score_id,
           ROUND(COALESCE(SUM(f.normalized_value * f.weight), 0), 2) AS bonus
    FROM score_factor f
             -- 同上：不限定的話，任何帶因子明細又有門檻可 JOIN 的評分
             -- 都會被重算 bonus_subtotal／final_score／grade
             JOIN v905_cur cur ON cur.score_id = f.score_id
    WHERE NOT f.is_penalty
      AND f.data_available
    GROUP BY f.score_id
),
calc AS (
    SELECT s.id,
           -- 有效權重四捨五入到小數三位後總和可能是 0.999 或 1.001，
           -- 上限保險，避免加分小計超出 [0, 100]
           LEAST(100, a.bonus) AS bonus,
           s.penalty_subtotal  AS penalty,
           gt.grade_a_min,
           gt.grade_b_min
    FROM product_score s
             JOIN agg a ON a.score_id = s.id
             JOIN grade_threshold gt ON gt.version_id = s.weight_version_id
                                    AND gt.scene_type = s.scene_type
)
UPDATE product_score s
SET bonus_subtotal = c.bonus,
    final_score    = GREATEST(0, c.bonus - c.penalty),
    grade = CASE
                -- §5.6 硬規則：扣分達 20 以上者分級最高只給 B
                WHEN GREATEST(0, c.bonus - c.penalty) >= c.grade_a_min
                     AND c.penalty < 20                                THEN 'A'
                WHEN GREATEST(0, c.bonus - c.penalty) >= c.grade_b_min THEN 'B'
                ELSE 'C'
            END
FROM calc c
WHERE s.id = c.id;


-- ===================================================================
-- 7. 歷史評分列
-- ===================================================================
-- 歷史列（V902 的 id 100 起）沒有因子明細，無法逐因子重算，
-- 沿用 V902 建立它們時的做法：以當期加分小計往前遞減，做出「近期上升」
-- 的走勢。V904 的二次換算把這些列也一起改壞了，這裡一併修回。
--
-- 分級用該列自己的權重版本門檻：10 週前那一筆記在 v1 名下（v2 自 60 天
-- 前才生效），AC-08-4 要的就是這種可回溯性。

--
-- 【關聯要帶 scene_type】歷史列取的是「同品項當期那筆的加分小計」。
-- 只用 product_id 關聯的話，同品項同期一旦有主、次兩個情境
-- （§FR-04 多情境評分正是要產生這種資料），一筆歷史列會同時對到兩筆
-- 當期分數，UPDATE ... FROM 用哪一筆沒有保證，結果不可重現。
-- 加上 scene_type 才是一對一。

WITH latest AS (
    -- 當期期別取自工作集本身，不從整張 product_score 推測
    SELECT MAX(s.period) AS period
    FROM product_score s JOIN v905_cur cur ON cur.score_id = s.id
),
cur AS (
    SELECT s.product_id, s.scene_type, s.bonus_subtotal
    FROM product_score s JOIN v905_cur c ON c.score_id = s.id
),
hist AS (
    SELECT h.id,
           h.penalty_subtotal AS penalty,
           -- 每往前一週減 1.4 分。週數由期別字串反推，不從 CURRENT_DATE 推
           -- （理由同第 3 段）：to_date 以 ISO 週還原該週星期一，兩者相減
           -- 除以 7 即為間隔週數。共用資料庫上得到 2026W33→1、2026W24→10。
           ROUND(GREATEST(0, LEAST(100,
                 c.bonus_subtotal
                 - ROUND((to_date(l.period, 'IYYY"W"IW')
                          - to_date(h.period, 'IYYY"W"IW')) / 7.0) * 1.4)), 2) AS bonus,
           gt.grade_a_min,
           gt.grade_b_min
    FROM product_score h
             CROSS JOIN latest l
             -- 只處理假資料的歷史列：屬於 seed 工作集、且不在當期工作集內
             JOIN v905_seed sd ON sd.score_id = h.id AND NOT sd.is_current
             JOIN cur c ON c.product_id = h.product_id
                       AND c.scene_type = h.scene_type
             JOIN grade_threshold gt ON gt.version_id = h.weight_version_id
                                    AND gt.scene_type = h.scene_type
)
UPDATE product_score s
SET bonus_subtotal = hist.bonus,
    final_score    = GREATEST(0, hist.bonus - hist.penalty),
    grade = CASE
                WHEN GREATEST(0, hist.bonus - hist.penalty) >= hist.grade_a_min
                     AND hist.penalty < 20                                     THEN 'A'
                WHEN GREATEST(0, hist.bonus - hist.penalty) >= hist.grade_b_min THEN 'B'
                ELSE 'C'
            END
FROM hist
WHERE s.id = hist.id;


-- ===================================================================
-- 8. 依 §5.9 重算信心度
-- ===================================================================
--   基準 100
--   −10  熱度來源降級（改為從 heat_source 讀，不再寫死）
--   −20  §5.3.1 未走同品類百分位（SIBLING 與 OVERALL 兩段都扣）
--   −8   每個缺資料的加分因子
--   −10  情境判定信心 < 0.7（含判定失敗退回 REPLENISHMENT 的情況）
--
-- 三段退路的扣分：v3.0 的表只區分「不扣」與「扣 20」，SIBLING 與
-- OVERALL 同樣扣 20，差別在 OVERALL 另需 UI 標示。
-- 資料庫沒有欄位承載「須標示」，前端依 §5.3.1 的規則自行判斷；
-- 本檔不為此新增欄位（那是 schema 變更，不屬假資料重算）。

WITH degraded AS (
    SELECT EXISTS (SELECT 1 FROM heat_source
                   WHERE enabled AND availability <> 'AVAILABLE') AS any_degraded
),
missing_factors AS (
    SELECT score_id, COUNT(*) AS n_missing
    FROM score_factor
    WHERE NOT is_penalty AND NOT data_available
    GROUP BY score_id
),
scene AS (
    SELECT DISTINCT ON (product_id) product_id, ai_confidence
    FROM scene_classification_log
    ORDER BY product_id, created_at DESC
),
calc AS (
    SELECT t.score_id AS id,
           GREATEST(0, 100
               - CASE WHEN d.any_degraded THEN 10 ELSE 0 END
               - CASE WHEN t.tier = 'CATEGORY' THEN 0 ELSE 20 END
               - 8 * COALESCE(mf.n_missing, 0)
               - CASE WHEN sc.product_id IS NULL THEN 0
                      WHEN sc.ai_confidence IS NULL OR sc.ai_confidence < 0.70 THEN 10
                      ELSE 0 END
           ) AS conf
    FROM v905_tier t
             CROSS JOIN degraded d
             LEFT JOIN missing_factors mf ON mf.score_id = t.score_id
             LEFT JOIN scene sc           ON sc.product_id = t.product_id
)
UPDATE product_score s
SET confidence = calc.conf
FROM calc
WHERE calc.id = s.id;

-- 歷史列沿用該品項當期的信心度：歷史列沒有因子明細，就地重算會少算
-- 「缺資料因子」那一項，走勢圖上的信心標記會忽高忽低。
UPDATE product_score h
SET confidence = c.confidence
FROM product_score c
         JOIN v905_cur vc ON vc.score_id = c.id
WHERE h.id IN (SELECT score_id FROM v905_seed WHERE NOT is_current)
  AND c.product_id = h.product_id
  -- 與第 7 段同理：同品項同期可能有主、次兩個情境，
  -- 只用 product_id 關聯會一對多，取到哪一筆沒有保證
  AND c.scene_type = h.scene_type;


-- ===================================================================
-- 9. 開團快照：只驗筆數，不再同步分數
-- ===================================================================
-- v3.0 §7.2.8 把 campaign_snapshot 精簡為六欄（V17 已套用），
-- 分數、分級、加減分小計都不再重複保存，改由 decision_record.score_id
-- join 回 product_score 取得。第 6、7 段重算完，快照上顯示的值自動就是對的。
--
-- 筆數檢查保留：假資料快照應為 10 筆，與 V903 的十筆決策一對一。
-- 少於 10 代表假資料被刪改過，本檔的重算範圍就不可信。

DO $$
DECLARE
    n_seed_snapshot INT;
BEGIN
    SELECT COUNT(*) INTO n_seed_snapshot
    FROM campaign_snapshot WHERE decision_id BETWEEN 1 AND 10;

    IF n_seed_snapshot <> 10 THEN
        RAISE EXCEPTION
            'V905 中止：假資料開團快照為 % 筆，應為 10 筆（V903 的決策 1–10 各一）',
            n_seed_snapshot;
    END IF;
END $$;


-- ===================================================================
-- 10. 校準報告改口徑，與 v3 草稿的新故事一致
-- ===================================================================
-- V903 的校準報告主張「heat_volume 未分配權重但相關係數與斜率相當，
-- 建議納入」，v3 草稿原本就是照這個主張建的。§5.2.1-a 把 HEAT_VOLUME
-- 降級為門檻條件之後，這份報告變成建議一件規格已經否決的事。
-- 改為主張 price_fit——即第 1 段 v3 草稿實際做的調整。
--
-- 樣本數仍是 6、p 值仍全部不顯著、status 仍停在 PENDING：
-- AC-15-1 的效度警示與 AC-15-3 的核准流程要靠這些值才演示得出來。

UPDATE calibration_report
SET regression_result = '{"method":"pearson","factors":[{"code":"TREND","correlation":0.71,"currentWeight":0.50,"suggestedWeight":0.47,"pValue":0.11},{"code":"MARGIN","correlation":0.22,"currentWeight":0.10,"suggestedWeight":0.10,"pValue":0.67},{"code":"CVR","correlation":0.58,"currentWeight":0.08,"suggestedWeight":0.08,"pValue":0.23},{"code":"PRICE_FIT","correlation":0.64,"currentWeight":0.07,"suggestedWeight":0.10,"pValue":0.14},{"code":"FESTIVAL","correlation":0.34,"currentWeight":0.15,"suggestedWeight":0.15,"pValue":0.51},{"code":"CLIMATE","correlation":0.19,"currentWeight":0.10,"suggestedWeight":0.10,"pValue":0.72}],"note":"sample_size=6，所有 p 值均未達 0.05，本結果不具統計顯著性"}'::jsonb,
    ai_interpretation =
        '樣本數僅 6 筆，任何一筆的變動都會顯著改變相關係數，因此以下觀察僅供方向參考，不足以支持權重調整。'
        || E'\n\n'
        || '值得注意的是 price_fit 的相關係數（0.64）明顯高於其 7% 權重所隱含的重要性，僅次於熱度斜率（0.71）。'
        || '§5.2.1 將價格帶適配度定義為售價偏離同品類價格帶的程度，在補貨型品項上尤其關鍵 —— '
        || '價格帶錯位的品項即使熱度與毛利都好，轉換率仍然拉不起來。'
        || '建議在樣本累積至 200 筆後優先驗證這一項。'
        || E'\n\n'
        || '另需留意：情境覆寫集中於「食品／零食」品類（6 筆決策中有 2 筆覆寫，皆為該品類），'
        || '若覆寫是系統性的，代表 SceneClassifierAgent 對節慶型品項的判定規則需要調整，'
        || '而不是權重的問題。'
-- 以固定 id 界定，不用 LIKE '%HEAT_VOLUME%'：那會命中任何組員建立的
-- 舊格式報告並整份覆寫。V903 只插入一筆 calibration_report，id 為 1。
WHERE id = 1;

-- 上一句依賴「id = 1 就是 V903 那筆」。萬一序列被動過或那筆被刪，
-- 這裡會發現改錯對象或什麼都沒改，而不是安靜地跳過。
DO $$
DECLARE
    n_seed_report INT;
BEGIN
    SELECT COUNT(*) INTO n_seed_report
    FROM calibration_report
    WHERE id = 1 AND sample_size = 6 AND status = 'PENDING';

    IF n_seed_report <> 1 THEN
        RAISE EXCEPTION
            'V905 中止：找不到 V903 的假資料校準報告（id=1、sample_size=6、status=PENDING）。'
            '第 10 段改到的可能不是預期的那一筆';
    END IF;
END $$;


-- ===================================================================
-- 11. 自我驗證：算錯就讓 migration 當場失敗
-- ===================================================================
-- 本檔幾乎全由 UPDATE ... FROM 構成，而 UPDATE 找不到列時不會報錯。
-- 撰寫過程已經踩過一次：第 2 段原本寫成 UPDATE，在乾淨資料庫上
-- grade_threshold 是空表，第 6、7 段整段靜默失效，分數完全沒被重算，
-- Flyway 卻記為 success。以下三項檢查把那種情況變成明確的失敗。

DO $$
DECLARE
    bad_weight   INT;
    bad_bonus    INT;
    bad_threshold INT;
    other_threshold INT;
BEGIN
    -- AC-08-1：本檔重建的三個版本，四組情境都必須存在且各自六因子加總 1.000。
    --
    -- 以 v1/v2/v3 × 四情境的完整組合為左表 LEFT JOIN，而不是直接對
    -- weight_profile 分組。差別在「整組不見」這種情況：直接分組時沒有列
    -- 就沒有分組，HAVING 永遠不會命中，驗證會安靜地通過。
    SELECT COUNT(*) INTO bad_weight FROM (
        SELECT v.version_id, v.scene_type
        FROM (SELECT unnest(ARRAY[1, 2, 3]) AS version_id) ver
                 CROSS JOIN (SELECT unnest(ARRAY['VIRAL', 'FESTIVAL',
                                                 'REPLENISHMENT', 'SEASONAL']) AS scene_type) sc
                 CROSS JOIN LATERAL (SELECT ver.version_id, sc.scene_type) v
                 LEFT JOIN weight_profile wp ON wp.version_id = v.version_id
                                            AND wp.scene_type = v.scene_type
        GROUP BY v.version_id, v.scene_type
        HAVING COUNT(wp.factor_code) <> 6
            OR COALESCE(ROUND(SUM(wp.weight), 3), 0) <> 1.000
    ) t;
    IF bad_weight > 0 THEN
        RAISE EXCEPTION 'V905 驗證失敗：有 % 組權重不是六因子或加總不等於 1.000', bad_weight;
    END IF;

    -- §5.5：加分小計必須等於因子明細的加權和，否則畫面上的長條加起來
    -- 對不上總分。容許 0.01 的四捨五入誤差。
    SELECT COUNT(*) INTO bad_bonus FROM (
        SELECT s.id
        FROM product_score s
                 -- 只驗本檔算過的列。掃全表的話，組員自己寫入的評分
                 -- 若加分小計對不上因子明細，會擋下整組人的啟動——
                 -- 那與第 1 段「不因別人的資料 RAISE」的取捨互相矛盾。
                 JOIN v905_cur cur ON cur.score_id = s.id
                 JOIN score_factor f ON f.score_id = s.id
                                    AND NOT f.is_penalty AND f.data_available
        GROUP BY s.id, s.bonus_subtotal
        HAVING ABS(s.bonus_subtotal - ROUND(SUM(f.normalized_value * f.weight), 2)) > 0.01
    ) t;
    IF bad_bonus > 0 THEN
        RAISE EXCEPTION 'V905 驗證失敗：有 % 筆評分的加分小計對不上因子明細', bad_bonus;
    END IF;

    -- 假資料的三個版本都要有完整四榜門檻，否則依版本分級時會 JOIN 不到列。
    --
    -- 限定 v1/v2/v3：本檔第 2 段只寫這三個版本的門檻，掃全表等於因為
    -- 組員草稿版本的門檻不完整就擋下整組人的啟動，與第 1 段的取捨矛盾。
    SELECT COUNT(*) INTO bad_threshold FROM (
        SELECT v.id FROM weight_version v
                 LEFT JOIN grade_threshold gt ON gt.version_id = v.id
        WHERE v.id IN (1, 2, 3)
        GROUP BY v.id HAVING COUNT(gt.scene_type) <> 4
    ) t;
    IF bad_threshold > 0 THEN
        RAISE EXCEPTION 'V905 驗證失敗：有 % 個假資料權重版本的四榜門檻不完整', bad_threshold;
    END IF;

    -- 其他版本的門檻缺漏只出聲，不擋。這是「建立 weight_version 時要
    -- 一併寫入四榜門檻」這個應用層缺口的徵狀，不是本檔算錯。
    SELECT COUNT(*) INTO other_threshold FROM (
        SELECT v.id FROM weight_version v
                 LEFT JOIN grade_threshold gt ON gt.version_id = v.id
        WHERE v.id NOT IN (1, 2, 3)
        GROUP BY v.id HAVING COUNT(gt.scene_type) <> 4
    ) t;
    IF other_threshold > 0 THEN
        RAISE NOTICE 'V905：有 % 個非假資料權重版本的四榜門檻不完整，'
                     '依該版本分級的查詢會 JOIN 不到列。'
                     '建立 weight_version 時須一併寫入四筆 grade_threshold（§FR-08）',
                     other_threshold;
    END IF;

    RAISE NOTICE 'V905 驗證通過：權重加總、加分小計與四榜門檻皆一致';
END $$;


-- ===================================================================
-- 12. 清理暫存表
-- ===================================================================
-- 刻意排在第 11 段之後：驗證要用 v905_cur 與 v905_seed 把檢查範圍
-- 限制在本檔算過的列。先 DROP 再驗證的話，驗證只能掃全表，
-- 別人寫入的評分就會擋下整組人的啟動。

DROP TABLE v905_tier;
DROP TABLE v905_cur;
DROP TABLE v905_seed;


-- ===================================================================
-- 收尾說明
-- ===================================================================
-- 本檔沒有寫死任何新的 id（PRICE_FIT 列走 identity 預設），
-- 因此不需要重跑 V903／V904 的序列收尾。
--
-- 套用到共用資料庫的程序見 db/migration/V13 檔尾：V902／V904 內容已改，
-- 需先把 flyway_schema_history 的 checksum 設為 NULL；
-- 本檔是新版本，沒有這個問題。
