-- ===================================================================
-- V903 開發用假資料（4/4）：匯入、銷售、決策閉環、尋源、校準、稽核
-- 收尾：把所有 identity 序列推到目前最大 id 之後
-- ===================================================================


-- ===================================================================
-- 品項與節慶關聯度（規格書 §7.2 item_festival_affinity、FR-17-1）
-- ===================================================================
-- 節慶因子 = 關聯度 × 時間窗權重(日期, 節慶日, 品類前置天數)。
-- 蛋黃酥對中秋是 1.0，對其他節慶接近 0 —— 這正是「節慶不是固定加分欄位，
-- 而是時間窗函數」的意思：同一個品項在不同時間點的節慶因子完全不同。

-- set_by／confirmed_by／confirmed_at 為 V17 新增（§7.2.10）：
-- AI 建議的關聯度需人工確認後才生效，未確認者不得進入評分。
-- 兩筆刻意留在 AI_SUGGESTED 且未確認，讓「待確認」的狀態有樣本可測。

INSERT INTO item_festival_affinity (product_id, festival_code, affinity, set_by, confirmed_by, confirmed_at) VALUES
  (107, 'MID_AUTUMN',     1.00, 'MANUAL',       NULL, NULL),
  (107, 'DOUBLE_11',      0.20, 'MANUAL',       NULL, NULL),
  (101, 'MID_AUTUMN',     0.45, 'MANUAL',       NULL, NULL),
  (101, 'CHRISTMAS',      0.55, 'AI_SUGGESTED', 1,    now() - interval '15 days'),
  (101, 'LUNAR_NEW_YEAR', 0.60, 'MANUAL',       NULL, NULL),
  (102, 'LUNAR_NEW_YEAR', 0.75, 'MANUAL',       NULL, NULL),
  (102, 'LANTERN',        0.40, 'MANUAL',       NULL, NULL),
  (106, 'FATHERS_DAY',    0.65, 'MANUAL',       NULL, NULL),
  (106, 'MID_AUTUMN',     0.50, 'MANUAL',       NULL, NULL),
  (110, 'LUNAR_NEW_YEAR', 0.70, 'MANUAL',       NULL, NULL),   -- 年前大掃除
  (109, 'LUNAR_NEW_YEAR', 0.65, 'MANUAL',       NULL, NULL),
  (113, 'DOUBLE_11',      0.80, 'AI_SUGGESTED', 2,    now() - interval '8 days'),
  (113, 'CHRISTMAS',      0.60, 'MANUAL',       NULL, NULL),
  (112, 'DOUBLE_11',      0.75, 'MANUAL',       NULL, NULL),
  -- 尚未確認：AI 建議但還沒有人按下確認，不得進入評分
  (114, 'DOUBLE_11',      0.85, 'AI_SUGGESTED', NULL, NULL),
  (114, 'DOUBLE_12',      0.70, 'AI_SUGGESTED', NULL, NULL),
  (117, 'BACK_TO_SCHOOL', 0.55, 'MANUAL',       NULL, NULL);


-- ===================================================================
-- 匯入批次與錯誤（規格書 §7.2 import_batch / import_error、FR-09）
-- ===================================================================
-- 三種結果都要有：全成功、部分成功（PARTIAL）、全失敗。
-- FR-09 明訂「部分成功時仍寫入正確列」，PARTIAL 這個狀態沒有資料就測不到。

-- data_type 值域自 V17 起對齊規格書 §7.2.11：SALES／REVIEW／AUDIENCE／PRODUCT。
-- status 值 SUCCESS 自 V18 起改為規格書 §7.2.7 的 SUCCEEDED。
-- file_size／is_async／finished_at 亦為 V17 新增：FR-09 的大檔非同步匯入
-- 需要「檔案多大、是不是背景跑、什麼時候跑完」三項資訊。
-- 超過 1 MB 的兩批設為 is_async，其中失敗那批的 finished_at 也有值——
-- 失敗一樣是結束。

INSERT INTO import_batch (id, data_type, file_name, file_size, total_rows, success_rows, fail_rows,
                          status, is_async, created_by, created_at, finished_at) VALUES
  (1, 'SALES',    '2026H1_團購銷售明細.xlsx', 2148352, 4820, 4820,   0, 'SUCCEEDED', TRUE,  3, now() - interval '30 days', now() - interval '30 days' + interval '4 minutes'),
  (2, 'REVIEW',   '蝦皮評論匯出_20260701.csv',  486210, 1260, 1244,  16, 'PARTIAL',   FALSE, 3, now() - interval '18 days', now() - interval '18 days' + interval '92 seconds'),
  (3, 'AUDIENCE', '會員輪廓_2026Q2.csv',        158944,  890,  890,   0, 'SUCCEEDED', FALSE, 3, now() - interval '12 days', now() - interval '12 days' + interval '38 seconds'),
  (4, 'PRODUCT',  '新品清單_0805.xlsx',          18620,   42,    0,  42, 'FAILED',    FALSE, 3, now() - interval '6 days',  now() - interval '6 days' + interval '5 seconds'),
  (5, 'SALES',    '2026Q3_團購銷售明細.xlsx',   612480, 1150, 1150,   0, 'SUCCEEDED', TRUE,  3, now() - interval '2 days',  now() - interval '2 days' + interval '71 seconds');

INSERT INTO import_error (batch_id, row_number, column_name, error_message, raw_row) VALUES
  (2, 118,  'rating',      '星等須介於 0 與 5 之間，取得值：6',                    '蝦皮,這個超好吃,6,2026-06-11'),
  (2, 274,  'reviewed_at', '日期格式無法解析，預期 yyyy-MM-dd，取得值：2026/6/30', '蝦皮,包裝完整,4.5,2026/6/30'),
  (2, 903,  'content',     '必填欄位為空',                                          '蝦皮,,5,2026-06-25'),
  (4, 1,    'category_id', '參照的品類不存在：id=99',                               '未命名品項,99,1,120,240'),
  (4, 2,    'cost',        '成本不可為負數，取得值：-15',                           '測試品項,10,1,-15,200'),
  (4, 3,    'name',        '必填欄位為空',                                          ',10,1,80,160');


-- ===================================================================
-- 銷售紀錄（規格書 §7.2 sales_record）
-- ===================================================================
-- conversion 因子（§5.2.1 歷史轉換率）的資料來源。
-- 用 generate_series 造近 180 日的資料：手寫幾千列不現實，
-- 而轉換率需要「一段時間的累積」才算得出有意義的數字。
--
-- impression 刻意讓一部分為 NULL —— 來源系統不一定給曝光數，
-- 而 §5.7 要求缺曝光數時轉換率標示為「無資料」而非 0。

-- audience_tag 於 V17 更名為 audience_code 並縮為 VARCHAR(24)（§7.2.11）：
-- 舊欄位放的是逗號分隔的人口統計標籤（'F18_24,F25_34'），join 不到任何東西；
-- 新欄位對應 audience_segment.audience_code，是單一代碼。
--
-- 這裡直接依成交價落在哪個客群的價格帶來配代碼，而不是隨手填：
-- PRICE_FIT 因子（§5.2.4）算的就是「這個價位與該品類客群組成的適配度」，
-- 價格與客群對不起來的話，那個因子的測試資料等於是假的。

INSERT INTO sales_record (order_date, product_id, product_name_raw, category_id,
                          price, qty, impression, audience_code, import_batch_id)
SELECT CURRENT_DATE - d,
       p.id,
       p.name,
       p.category_id,
       p.suggested_price,
       -- 以品項 id 與日期做出可重現但看起來隨機的銷量
       GREATEST(1, (18 + (p.id % 7) * 4 + (sin((d + p.id) / 6.0) * 9)::int)),
       CASE WHEN p.id % 5 = 0 THEN NULL          -- 兩成品項沒有曝光數
            ELSE GREATEST(50, (620 + (p.id % 11) * 45 + (cos(d / 8.0) * 120)::int)) END,
       CASE WHEN p.suggested_price < 150  THEN 'PRICE_SENSITIVE'
            WHEN p.suggested_price >= 600 THEN 'PREMIUM'
            ELSE                               'MAIN' END,
       CASE WHEN d > 60 THEN 1 ELSE 5 END
FROM product p
         CROSS JOIN generate_series(0, 179, 3) AS d      -- 每 3 天一筆，避免資料量過大
WHERE p.track_type = 'A'
  AND p.status IN ('LISTED', 'ADOPTED')
  AND p.suggested_price IS NOT NULL;

-- 比對不到品項的列：product_id 為 NULL 但保留原始名稱，供後續人工對應
INSERT INTO sales_record (order_date, product_id, product_name_raw, category_id,
                          price, qty, impression, audience_code, import_batch_id) VALUES
  (CURRENT_DATE - 40, NULL, '日式抹茶餅乾(舊品名)', 10, 119.00, 32, 540, 'PRICE_SENSITIVE', 1),
  (CURRENT_DATE - 38, NULL, '黑糖珍珠奶茶包 15入',   11,  89.00, 58, 810, 'PRICE_SENSITIVE', 1),
  -- 連客群都比對不到：audience_code 為 NULL，§5.7 應標為無資料而非 0
  (CURRENT_DATE - 22, NULL, '未知品項A',             NULL, 250.00, 4, NULL, NULL,            1);


-- ===================================================================
-- 評論與分析（規格書 §7.2 product_review / review_analysis）
-- ===================================================================
-- content_hash 用 PostgreSQL 內建的 sha256 算，不寫死字串 ——
-- 寫死的話一改內容就對不起來，而唯一鍵 uk_review 正是靠它去重。
-- （sha256 回傳 bytea，encode 成 hex 共 64 字元，剛好對上欄位長度。）

INSERT INTO product_review (product_id, source, content, rating, reviewed_at, content_hash)
SELECT r.product_id, r.source, r.content, r.rating, r.reviewed_at,
       encode(sha256(convert_to(r.content, 'UTF8')), 'hex')
FROM (VALUES
    (101, '蝦皮', '抹茶味很濃但不會苦，夾心比例剛好，回購第三次了', 5.0, CURRENT_DATE - 12),
    (101, '蝦皮', '外盒有壓痕，餅乾本身是完好的，客服處理很快',      4.0, CURRENT_DATE - 15),
    (101, '蝦皮', '送禮自用都適合，包裝質感不錯',                    5.0, CURRENT_DATE - 18),
    (101, 'Facebook 社團', '夏天收到有點軟掉，建議加保冷',           3.0, CURRENT_DATE - 9),
    (101, '蝦皮', '比想像中小一點，但味道真的好',                    4.0, CURRENT_DATE - 6),
    (104, '蝦皮', '送到已經退冰變形，整盒都糊掉了',                  1.0, CURRENT_DATE - 20),
    (104, '蝦皮', '外包裝濕透，冷凍宅配的溫層真的要檢討',            1.5, CURRENT_DATE - 17),
    (104, '蝦皮', '口感和店裡吃的差很多，加熱後偏濕',                2.0, CURRENT_DATE - 14),
    (104, '蝦皮', '味道可以，但配送時間拖太久',                      3.0, CURRENT_DATE - 11),
    (104, '官網', '第二次訂就正常了，可能是上次配送問題',            4.0, CURRENT_DATE - 5),
    (113, '蝦皮', '顯色很漂亮而且不乾，這點很加分',                  5.0, CURRENT_DATE - 8),
    (113, '蝦皮', '色號選擇多，買了三支',                            5.0, CURRENT_DATE - 7),
    (113, '蝦皮', '持久度普通，吃東西後要補',                        3.5, CURRENT_DATE - 4),
    (116, '蝦皮', '香精味太重，喝起來不像手標',                      2.0, CURRENT_DATE - 25),
    (116, '蝦皮', '結塊嚴重，沖不開',                                1.5, CURRENT_DATE - 23),
    (116, '蝦皮', '還可以啦，就一般的奶茶粉',                        3.0, CURRENT_DATE - 21),
    (107, '官網', '去年買過，今年一樣好吃，蛋黃很飽滿',              5.0, CURRENT_DATE - 3),
    (105, '蝦皮', '珍珠煮起來很Q，小孩很愛',                         5.0, CURRENT_DATE - 10),
    (105, '蝦皮', '份量足，價格合理',                                4.5, CURRENT_DATE - 2)
) AS r(product_id, source, content, rating, reviewed_at);

-- 分析輸出是風險主題分類，不再是加分用的情感分數（§5.2.2）。
--
-- aspects（v2.0 的逗號分隔字串）已於 V17 移除：§7.2.4 明訂改為結構化的
-- risk_topic 列舉，與 §6.3 Agent 2 的輸出對齊。risk_topic 由 V906 依內容回填，
-- 且僅 NEGATIVE 的列有值（ck_review_analysis_risk_topic_only_negative）。
--
-- sentiment 則是明確保留：負評率（§5.2.2）與情緒分佈（§FR-05）都靠它。
INSERT INTO review_analysis (review_id, sentiment, risk_topic, key_phrase, model, prompt_version, analyzed_at)
SELECT r.id,
       CASE WHEN r.rating >= 4 THEN 'POSITIVE'
            WHEN r.rating >= 3 THEN 'NEUTRAL'
            ELSE 'NEGATIVE' END,
       -- risk_topic 僅 NEGATIVE 時有值（ck_review_analysis_risk_topic_only_negative）。
       -- 分類直接由評論內容判定：aspects 那個逗號分隔字串已隨 V17 消失，
       -- 再靠它推導就是在依賴一個不存在的欄位。
       CASE WHEN r.rating >= 3 THEN NULL
            WHEN r.content LIKE '%退冰%' OR r.content LIKE '%濕透%'
              OR r.content LIKE '%壓痕%' OR r.content LIKE '%變形%' THEN 'SHIPPING_DAMAGE'
            WHEN r.content LIKE '%結塊%' OR r.content LIKE '%香精%'
              OR r.content LIKE '%口感%'                            THEN 'QUALITY'
            WHEN r.content LIKE '%貴%'   OR r.content LIKE '%價格%' THEN 'PRICE'
            ELSE 'OTHER' END,
       CASE WHEN r.content LIKE '%抹茶%' THEN '抹茶不苦澀'
            WHEN r.content LIKE '%退冰%' THEN '冷鏈失溫'
            WHEN r.content LIKE '%顯色%' THEN '顯色不乾'
            ELSE NULL END,
       'meta-llama/llama-3.3-70b-instruct:free',
       'rr-v2.0',
       now() - interval '2 days'
FROM product_review r;


-- ===================================================================
-- AI 任務（規格書 §7.2 ai_task / ai_task_item、FR-07）
-- ===================================================================
-- 三個預算池都要有任務，AC-07-2「B 軌耗盡不影響 A 軌」才驗證得了。
-- total_cost_usd 全為 0：§3.2 一律使用免費模型。

-- V17 起的三個新欄位：
--   budget_pool     計入哪個預算池。三個池都要有任務，
--                   AC-07-2「B 軌耗盡不影響 A 軌」才驗證得了
--   request_count   已消耗請求數，是配額的主要度量（§7.2.7）；
--                   含重試與備援，所以會大於 total_count
--   cache_hit_count 命中快取的項目數，不計入配額
-- 狀態值 SUCCESS 亦於 V17 改為規格書的 SUCCEEDED。

INSERT INTO ai_task (id, task_type, budget_pool, status, total_count, success_count, fail_count,
                     cache_hit_count, request_count, total_cost_usd, created_by, started_at, finished_at) VALUES
  (1, 'SCENE_CLASSIFY',     'TRACK_A', 'SUCCEEDED', 17, 17,  0, 2, 17, 0, 3, now() - interval '2 days' - interval '40 minutes', now() - interval '2 days' - interval '31 minutes'),
  (2, 'REVIEW_RISK',        'TRACK_A', 'SUCCEEDED', 19, 19,  0, 0, 21, 0, 3, now() - interval '2 days' - interval '30 minutes', now() - interval '2 days' - interval '18 minutes'),
  (3, 'SELLING_POINT',      'TRACK_A', 'PARTIAL',   17, 14,  3, 0, 20, 0, 3, now() - interval '2 days' - interval '18 minutes', now() - interval '2 days' - interval '4 minutes'),
  (4, 'RECOMMENDATION',     'TRACK_A', 'RUNNING',   17,  9,  0, 1, 10, 0, 1, now() - interval '12 minutes', NULL),
  (5, 'SOURCING_SCOUT',     'TRACK_B', 'SUCCEEDED',  4,  4,  0, 0,  4, 0, 1, now() - interval '4 days', now() - interval '4 days' + interval '6 minutes'),
  (6, 'WEIGHT_CALIBRATION', 'RETRY',   'SUCCEEDED',  1,  1,  0, 0,  1, 0, 2, now() - interval '5 days', now() - interval '5 days' + interval '3 minutes'),
  -- 校準與整批失敗後的重試同池：v3.0 合併為三池，校準併入「重試與臨時任務」（FR-07 L883）
  (7, 'TREND_INTERPRET',    'RETRY',   'FAILED',     6,  0,  6, 0, 12, 0, 3, now() - interval '1 days', now() - interval '1 days' + interval '2 minutes');

-- raw_response 已於 V17 移除（§7.2.7 明訂）：LLM 原始回應可能含經模型改寫的
-- 評論片段，長期存 DB 會擴大機敏資料暴露面。除錯所需資訊改記應用日誌（§10）。
-- 原本靠 raw_response 說明「模型到底回了什麼」的三筆失敗，改寫進 error_message。
--
-- keyword_id 為 V17 新增：以關鍵字為標的的任務（TREND_INTERPRET／SOURCING_SCOUT）
-- 過去無欄位可指向標的，只能塞 product_id 或留白。
-- status 值域亦對齊規格書：SUCCEEDED／SKIPPED_CACHE／SKIPPED_QUOTA 三個是新的。

INSERT INTO ai_task_item (task_id, product_id, keyword_id, status, error_message, duration_ms) VALUES
  (3, 112, NULL, 'FAILED', 'JSON Schema 驗證失敗：points[0] 缺少必要欄位 evidence', 4210),
  (3, 114, NULL, 'FAILED', 'JSON Schema 驗證失敗：回應非合法 JSON（模型回了純文字而非 JSON）', 3880),
  (3, 118, NULL, 'FAILED', '品項資料不足：缺少成本與售價，無法產生賣點', 120),
  (3, 101, NULL, 'SUCCEEDED', NULL, 5120),
  (3, 104, NULL, 'SUCCEEDED', NULL, 6340),
  (3, 113, NULL, 'SUCCEEDED', NULL, 4890),
  -- 命中快取與配額耗盡：兩個狀態都是 v3.0 才有欄位可表達的
  (4, 105, NULL, 'SKIPPED_CACHE', NULL, 12),
  (4, 115, NULL, 'SKIPPED_QUOTA', '本日 TRACK_A 配額已用罄，順延至下批', 8),
  -- 整批失敗：上游服務不可用。FR-07 的「重跑失敗項」就是拿這種資料測。
  -- 這是關鍵字層級的任務，所以填 keyword_id 而非 product_id。
  (7, NULL, 1,  'FAILED', 'OpenRouter 回應 429 Too Many Requests，已達免費模型每日配額', 890),
  (7, NULL, 7,  'FAILED', 'OpenRouter 回應 429 Too Many Requests，已達免費模型每日配額', 760),
  (7, NULL, 16, 'FAILED', 'OpenRouter 回應 429 Too Many Requests，已達免費模型每日配額', 810),
  -- B 軌尋源：標的是關鍵字
  (5, NULL, 20, 'SUCCEEDED', NULL, 21400),
  -- 非品項也非關鍵字層級的任務：兩個都是 NULL
  (6, NULL, NULL, 'SUCCEEDED', NULL, 18240);


-- ===================================================================
-- 決策與快照（規格書 §7.2 decision_record / campaign_snapshot、FR-11）
-- ===================================================================
-- ai_insight_id 用子查詢帶入而非寫死 id：那些列的 id 由資料庫產生，
-- 寫死會在重跑 seed 時對到別筆。
--
-- 涵蓋的情境：
--   * 採納 AI 建議（followed_ai = TRUE）
--   * 未採納 AI 建議 → reason 必填（AC-11-2）
--   * 三種決策類型 ADOPT / WATCH / REJECT 都有
--   * 已回填與未回填（其中兩筆已逾 7 日未回填，觸發 AC-11-3 的儀表板提醒）

-- V17 依 §7.2.8 新增五組欄位：
--   ai_action / ai_qty_min / ai_qty_max  AI 建議的動作與數量區間。
--       followed_ai 的定義就是 decision == ai_action，有了這欄才比對得出來
--   campaign_end_date                    結案日期，是回填提醒的起算點（§FR-11-2）。
--       v2.0 要顯示「逾期天數」卻沒有任何欄位承載結案時間，那個計算做不出來
--   reviewed_by / reviewed_at            覆核者
--
-- 待回填的判準因此變成「campaign_end_date 已過 7 日且無 campaign_result」：
-- 決策 4（中秋蛋黃酥）與決策 5（奶茶粉）就是 AC-11-3 儀表板要抓出來的兩筆。

INSERT INTO decision_record (id, product_id, score_id, decision, ai_action, followed_ai, ai_insight_id,
                             first_order_qty, ai_qty_min, ai_qty_max,
                             expected_list_date, campaign_end_date, reason,
                             reviewed_by, reviewed_at, decided_by, decided_at) VALUES
  (1, 101, 1, 'ADOPT', 'ADOPT', TRUE,
   (SELECT id FROM ai_insight WHERE product_id = 101 AND insight_type = 'RECOMMENDATION' AND is_current),
   600, 500, 700, CURRENT_DATE - 21, CURRENT_DATE - 16, '熱度與毛利都在中上，採納 AI 建議數量',
   2, now() - interval '34 days', 1, now() - interval '35 days'),

  (2, 105, 5, 'ADOPT', 'ADOPT', TRUE, NULL,
   800, 700, 900, CURRENT_DATE - 45, CURRENT_DATE - 32, '常態補貨，轉換率穩定',
   NULL, NULL, 1, now() - interval '55 days'),

  (3, 104, 4, 'WATCH', 'WATCH', TRUE,
   (SELECT id FROM ai_insight WHERE product_id = 104 AND insight_type = 'RECOMMENDATION' AND is_current),
   NULL, NULL, NULL, NULL, NULL, '冷鏈負評集中，先解決配送再談開團',
   NULL, NULL, 1, now() - interval '2 days'),

  -- 未採納 AI 建議：AC-11-2 要求理由必填。
  -- 結案日已過 12 天仍未回填，屬 AC-11-3 的待辦。
  (4, 107, 7, 'ADOPT', 'WATCH', FALSE, NULL,
   400, 200, 300, CURRENT_DATE - 26, CURRENT_DATE - 12,
   'AI 建議首批 250，但中秋禮盒去年同期兩天完售且補單來不及，改抓 400',
   2, now() - interval '10 days', 2, now() - interval '10 days'),

  -- 同樣逾期未回填。REJECT 也要回填結果：沒開團也是一種結果
  (5, 116, 16, 'REJECT', 'WATCH', FALSE, NULL,
   NULL, NULL, NULL, NULL, CURRENT_DATE - 9,
   'AI 建議觀察，但同品類已有兩支開團中的奶茶品項，客層完全重疊，直接淘汰避免內耗',
   NULL, NULL, 2, now() - interval '9 days'),

  (6, 111, 11, 'ADOPT', 'ADOPT', TRUE, NULL,
   300, 250, 400, CURRENT_DATE - 30, CURRENT_DATE - 19, '轉換率表現好，客群明確',
   NULL, NULL, 1, now() - interval '40 days'),

  (7, 109, 9, 'ADOPT', 'ADOPT', TRUE, NULL,
   500, 450, 600, CURRENT_DATE - 60, CURRENT_DATE - 46, '日用品剛需，補貨型',
   NULL, NULL, 1, now() - interval '70 days'),

  (8, 110, 10, 'ADOPT', 'ADOPT', TRUE, NULL,
   200, 150, 250, CURRENT_DATE - 90, CURRENT_DATE - 76, '轉換率同品類最高',
   NULL, NULL, 1, now() - interval '100 days'),

  -- 這筆刻意設成「未採納 AI 且情境被覆寫」，而且它有結案結果。
  -- FR-11-3 的覆寫率與 AI 採納率只統計「已回填」的樣本，
  -- 若已回填的都是採納且未覆寫，那兩個指標會固定顯示 0% 與 100%，
  -- 組員開發那個畫面時會誤以為算錯了。
  (9, 102, 2, 'ADOPT', 'ADOPT', FALSE, NULL,
   350, 500, 600, CURRENT_DATE - 12, CURRENT_DATE - 6,
   'AI 依熱度斜率判為話題爆款型並建議首批 550，但草莓年糕是明確的冬季品項，'
   || '去年同期熱度曲線幾乎一樣，屬季節導向；已改判情境並下修首批至 350',
   2, now() - interval '19 days', 6, now() - interval '20 days'),

  (10, 113, 13, 'WATCH', 'WATCH', TRUE, NULL,
   NULL, NULL, NULL, NULL, NULL, '熱度剛起，再觀察兩週確認不是短期波動',
   NULL, NULL, 6, now() - interval '3 days');

-- 開團快照：v3.0 §7.2.8 精簡為六欄，只存無法從 product_score + score_factor
-- 還原的資訊。分數、分級、加減分小計、因子值全部拿掉——那些 join 回去就有，
-- 而 §5.10 已保證 score 永不覆寫、decision_record.score_id 也已綁定。
--
-- 留下來的三份 JSON 才是真的還原不了的：當下各來源的可用狀態、當下實際採用
-- 的合成權重、當下該榜的 A／B 門檻。這三項都會隨設定改變而失去歷史值。
INSERT INTO campaign_snapshot (decision_id, source_availability, applied_composite_weights,
                               applied_thresholds, scene_overridden, created_at)
SELECT d.id,
       '{"THREADS":"AVAILABLE","GOOGLE_TRENDS":"AVAILABLE","INSTAGRAM":"DEGRADED","MANUAL":"AVAILABLE"}'::jsonb,
       -- 與 heat_composite_daily.applied_weights 同一組數字：INSTAGRAM 降級後
       -- 其權重歸零並由其餘三來源重新正規化（§5.3.2）
       '{"THREADS": 0.412, "GOOGLE_TRENDS": 0.353, "INSTAGRAM": 0.000, "MANUAL": 0.235}'::jsonb,
       jsonb_build_object('sceneType', s.scene_type,
                          'gradeAMin', gt.grade_a_min,
                          'gradeBMin', gt.grade_b_min),
       -- 品項 107、104、102 的情境曾被人工覆寫（見 V902 的 scene_classification_log）
       d.product_id IN (107, 104, 102),
       d.decided_at
FROM decision_record d
         JOIN product_score s ON s.id = d.score_id
         LEFT JOIN grade_threshold gt ON gt.version_id = s.weight_version_id
                                     AND gt.scene_type = s.scene_type;


-- ===================================================================
-- 結案回填（規格書 §7.2.8 campaign_result、FR-11-2）
-- ===================================================================
-- v2.0 的 decision_feedback 已於 v3.0 廢除（§7.2.8 L3007），功能完全由本表
-- 取代，該表已由 V17 drop，本檔不再寫入。
--
-- 六筆已回填、四筆未回填。決策 4（中秋蛋黃酥）與決策 5（奶茶粉）的
-- campaign_end_date 已過 7 天仍未回填 —— 這正是 AC-11-3 儀表板待辦區
-- 要抓出來的兩筆。
--
-- 所有比率欄位自 V17 起統一為 DECIMAL(5,4)、值域 0–1（§7.2.8 L3020），
-- 且 realized_margin 更名為 realized_margin_rate 以消除「金額或率」的歧義。
-- 所以這裡寫的是 0.0120 而不是 1.20。
--
-- 這批資料同時是 FR-15 統計迴歸的標籤。六筆遠不及 AC-15-1 要求的 200 筆，
-- 所以校準報告的 status 只能是 PENDING、畫面必須顯示效度警示 ——
-- 這個「樣本不足」的狀態本身就是要被測到的狀態，不是資料沒造完。

INSERT INTO campaign_result (decision_id, actual_qty, sellout_status, return_rate,
                             realized_margin_rate, post_note_code, post_note_text, filled_by, filled_at) VALUES
  (1, 742, 'EARLY_SELLOUT', 0.0120, 0.5010, 'FASTER_THAN_EXPECTED', '開團第 2 天就補了一次單',     1, now() - interval '14 days'),
  (2, 810, 'ON_TIME',       0.0080, 0.5940, NULL,                    NULL,                           1, now() - interval '30 days'),
  -- HEAT_PASSED 是規格書的值；本庫舊值 HEAT_FADED 已於 V17 一併改名
  (6, 268, 'BELOW_TARGET',  0.0210, 0.4890, 'HEAT_PASSED',           '開團時討論度已經比評估時低',   1, now() - interval '18 days'),
  (7, 512, 'ON_TIME',       0.0150, 0.5020, NULL,                    NULL,                           1, now() - interval '45 days'),
  (8, 196, 'ON_TIME',       0.0060, 0.4110, 'OTHER',                 '無特殊狀況，照計畫出清',       1, now() - interval '75 days'),
  -- 滯銷案例：迴歸需要低分低銷量的樣本，全是成功案例的話相關係數毫無意義
  (9,  88, 'SLOW',          0.0430, 0.4420, 'QUALITY_ISSUE',         '收到反映外包裝受潮，退貨偏多', 6, now() - interval '5 days');


-- ===================================================================
-- B 軌尋源候選（規格書 §7.2 sourcing_candidate、FR-16-2）
-- ===================================================================
-- time_gap_days 必須等於 estimated_lifespan_days − lead_time_days
-- （資料庫端有 CHECK 約束，寫錯會直接插不進去）。
-- lead_time_days 取自 category_lead_time，符合 AC-17-3 的單一來源原則。
--
-- 主關聯自 V17 起是 product_id 而非 keyword_id（§7.2.9 v3.0 裁決）：
-- 綁關鍵字與 product.track_type 的模型互斥，AC-16-5「成案轉軌後熱度資料
-- 完整保留」沒有實作路徑。改綁品項後，轉軌只需改 track_type。
-- keyword_id 保留為「當初從哪個關鍵字挖出來」的來源紀錄，不是即時關聯。
--
-- status 欄位也在 V17 移除：狀態一律以 product.sourcing_status 為準
-- （§7.2.9 L3043），不重複於本表。四個候選對應的品項各自帶著
-- PENDING／SOURCING／URGENT／REJECTED，四種顏色照樣看得到。
--
-- 四筆分別落在時效落差的三個區段：
--   > +14 天  可行，正常排序
--   0 ～ +14  高風險，需加速尋源
--   < 0       淘汰（AC-16-4：自動標記且不可加入清單）

INSERT INTO sourcing_candidate (product_id, keyword_id, category_id, heat_stage, stage_weeks,
                                estimated_lifespan_days, lead_time_days, lead_time_overridden_by,
                                time_gap_days, scout_report, scouted_at) VALUES
  -- 上升期 → 壽命 56 天；零食前置期 45 天 → 落差 +11，需加速
  (120, 20, 10, 'RISING', 1, 56, 45, NULL, 11,
   '小紅書近兩週出現大量開箱，台灣尚無代理。已找到兩家韓國中盤有現貨，但最快出貨仍需 6 週；建議同步詢問國內代工可行性。機會訊號：話題新鮮度高、單價帶適合團購。風險訊號：品項為冷藏甜點，夏季配送成本高。',
   now() - interval '4 days'),

  -- 高原期第 3 週 → 壽命 35 天；零食前置期 45 天 → 落差 −10，狀態為 REJECTED
  (121, 21, 10, 'PLATEAU', 3, 35, 45, NULL, -10,
   '熱度已在高原期第三週，依 §5.8 推估剩餘壽命 35 天，而零食類進口前置期 45 天。時效落差為負，來不及在熱度消退前上架。保留紀錄供下次同關鍵字出現時參考。',
   now() - interval '4 days'),

  -- 上升期 → 壽命 56 天；小家電前置期 30 天，但採購覆寫為 30（未改值，僅記錄覆寫者）→ 落差 +26
  (122, 22, 40, 'RISING', 2, 56, 30, 1, 26,
   '夏季小物，討論穩定成長且尚未見頂。已聯繫三家供應商，其中一家可提供 OEM 並接受 500 起訂。機會訊號：時效落差充裕、可做客製印刷。風險訊號：同類商品去年已有品牌操作過，需確認差異點。',
   now() - interval '4 days'),

  -- 衰退期 → 壽命 17 天；美妝前置期 35 天 → 落差 −18
  (123, 23, 30, 'DECLINING', 5, 17, 35, NULL, -18,
   '熱度已進入衰退期，剩餘壽命推估 17 天，美妝類前置期 35 天。時效落差為負，不可加入尋源清單。',
   now() - interval '4 days');


-- ===================================================================
-- 權重校準報告（規格書 §7.2 calibration_report、FR-15）
-- ===================================================================
-- 樣本數 6，遠低於 AC-15-1 的 200 筆門檻 → 畫面須顯示不可關閉的效度警示，
-- 且 status 停在 PENDING（AC-15-3：須經 BUYER_LEAD 核准才產生新版本）。
--
-- regression_result 是統計模組的產出，ai_interpretation 是 AI 的解讀。
-- AC-15-2：建議權重只能來自前者，AI 不得自行產生數值 ——
-- 所以 suggestedWeight 這些數字全部落在 regression_result 裡面。

-- accepted_items 為 V17 依 §7.2.11 新增：部分採納時逐項勾選的結果。
-- 本筆停在 PENDING（樣本不足），還沒有人勾選，所以是 NULL 而不是空陣列——
-- 空陣列的意思是「看過了，一項都不採納」，兩者不同。

INSERT INTO calibration_report (quarter, sample_size, regression_result, ai_interpretation,
                                backtest_result, status, accepted_items,
                                reviewed_by, reviewed_at, created_at) VALUES
  (to_char(CURRENT_DATE, 'YYYY') || 'Q' || to_char(CURRENT_DATE, 'Q'), 6,
   '{"method":"pearson","factors":[{"code":"TREND","correlation":0.71,"currentWeight":0.55,"suggestedWeight":0.42,"pValue":0.11},{"code":"PRICE_FIT","correlation":0.68,"currentWeight":0.00,"suggestedWeight":0.13,"pValue":0.14},{"code":"MARGIN","correlation":0.22,"currentWeight":0.10,"suggestedWeight":0.10,"pValue":0.67},{"code":"CVR","correlation":0.58,"currentWeight":0.10,"suggestedWeight":0.10,"pValue":0.23},{"code":"FESTIVAL","correlation":0.34,"currentWeight":0.15,"suggestedWeight":0.15,"pValue":0.51},{"code":"CLIMATE","correlation":0.19,"currentWeight":0.10,"suggestedWeight":0.10,"pValue":0.72}],"note":"sample_size=6，所有 p 值均未達 0.05，本結果不具統計顯著性"}'::jsonb,

   '樣本數僅 6 筆，任何一筆的變動都會顯著改變相關係數，因此以下觀察僅供方向參考，不足以支持權重調整。'
   || E'\n\n'
   || '值得注意的是 PRICE_FIT 目前未分配權重，但其與實際銷量的相關係數（0.68）與熱度斜率（0.71）相當。'
   || '這與 §5.2.4 對該因子的設計意圖一致：客群價格帶適配是全系統唯一對應痛點 P-3 的因子，'
   || '而 v3.0 新增的 audience_segment／category_audience_mix 讓它終於算得出來。'
   || '建議在樣本累積至 200 筆後優先驗證這一項。'
   || E'\n\n'
   || '另需留意：情境覆寫集中於「食品／零食」品類（6 筆決策中有 2 筆覆寫，皆為該品類），'
   || '若覆寫是系統性的，代表 SceneClassifierAgent 對節慶型品項的判定規則需要調整，'
   || '而不是權重的問題。',

   '{"backtests":[{"scheme":"EQUAL_WEIGHT","correlation":0.41,"gradeAHitRate":0.50},{"scheme":"CURRENT_V2","correlation":0.63,"gradeAHitRate":0.67},{"scheme":"SUGGESTED_V3","correlation":0.69,"gradeAHitRate":0.67}],"note":"回測樣本同為 6 筆，差異落在雜訊範圍內"}'::jsonb,

   'PENDING', NULL, NULL, NULL, now() - interval '5 days');


-- v3 草稿版本的來源就是這份校準報告（§7.2.5 source_calibration_id）。
-- 用子查詢帶入而非寫死 id：那一列的 id 由資料庫產生。
UPDATE weight_version
SET source_calibration_id = (SELECT id FROM calibration_report
                             WHERE quarter = to_char(CURRENT_DATE, 'YYYY') || 'Q' || to_char(CURRENT_DATE, 'Q'))
WHERE version_no = 'v3';


-- ===================================================================
-- 稽核紀錄（規格書 §7.2 audit_log）
-- ===================================================================

INSERT INTO audit_log (user_id, action, entity_type, entity_id, before_json, after_json, ip, created_at) VALUES
  (2, 'APPROVE', 'WeightVersion', 2,
   '{"status":"DRAFT","effectiveFrom":null}'::jsonb,
   -- 用 jsonb_build_object 而不是字串串接：::jsonb 的優先序高於 ||，
   -- 寫成 '...' || x || '"}'::jsonb 會先把 '"}' 當成 JSON 去解析而報錯
   jsonb_build_object('status', 'APPROVED', 'isCurrent', true, 'effectiveFrom', (CURRENT_DATE - 60)::text),
   '203.0.113.42', now() - interval '62 days'),
  (2, 'RETIRE',  'WeightVersion', 1,
   '{"status":"APPROVED","isCurrent":true}'::jsonb, '{"status":"RETIRED","isCurrent":false}'::jsonb,
   '203.0.113.42', now() - interval '62 days'),
  (1, 'UPDATE',  'Product', 101,
   '{"status":"ADOPTED","suggestedPrice":125.00}'::jsonb,
   '{"status":"LISTED","suggestedPrice":129.00}'::jsonb,
   '198.51.100.17', now() - interval '21 days'),
  (2, 'OVERRIDE_SCENE', 'Product', 107,
   '{"sceneType":"SEASONAL","source":"AI"}'::jsonb,
   '{"sceneType":"FESTIVAL","source":"MANUAL"}'::jsonb,
   '203.0.113.42', now() - interval '2 days'),
  (4, 'UPDATE',  'HeatSource', 3,
   '{"availability":"AVAILABLE","quotaUsed":140}'::jsonb,
   '{"availability":"DEGRADED","quotaUsed":195}'::jsonb,
   '192.0.2.88', now() - interval '2 days'),
  (2, 'DELETE',  'Product', 999,
   '{"name":"測試品項（誤建）","categoryId":10}'::jsonb, NULL,
   '203.0.113.42', now() - interval '48 days'),
  -- 系統排程觸發：user_id 為 NULL
  (NULL, 'SCHEDULED_RESCORE', 'ProductScore', NULL,
   NULL, jsonb_build_object('period', to_char(CURRENT_DATE, 'IYYY"W"IW'), 'affected', 17),
   NULL, now() - interval '2 days');


-- ===================================================================
-- 報表任務（規格書 §7.2.11 report_job、FR-12）
-- ===================================================================
-- v3.0 新增的表。v2.0 有三個報表端點卻沒有任何資料表承載任務狀態與檔案位置，
-- 「下載」這個動作根本無從實作。
--
-- 四種狀態都有：排隊中、執行中、已完成、失敗。
-- SUCCEEDED 的兩筆一定要有 file_path（ck_report_job_file 會擋），
-- 這正是這張表存在的理由。

INSERT INTO report_job (id, report_type, format, params_json, status, file_path, row_count,
                        requested_by, requested_at, finished_at) VALUES
  (1, 'WEEKLY_PICK',    'PDF',
   jsonb_build_object('period', to_char(CURRENT_DATE, 'IYYY"W"IW'), 'sceneType', 'VIRAL', 'topN', 20),
   'SUCCEEDED', '/reports/2026/weekly_pick_viral.pdf', 17, 2, now() - interval '2 days', now() - interval '2 days' + interval '22 seconds'),
  (2, 'SCORE_DETAIL',   'XLSX',
   '{"productId": 101}'::jsonb,
   'SUCCEEDED', '/reports/2026/score_detail_101.xlsx', 9, 1, now() - interval '1 days', now() - interval '1 days' + interval '6 seconds'),
  (3, 'ACCURACY',       'PDF',
   '{"quarter": "2026Q2"}'::jsonb,
   'FAILED', NULL, NULL, 2, now() - interval '6 hours', now() - interval '6 hours' + interval '3 seconds'),
  (4, 'SOURCING_QUEUE', 'XLSX',
   '{"includeRejected": false}'::jsonb,
   'RUNNING', NULL, NULL, 1, now() - interval '30 seconds', NULL),
  (5, 'CALIBRATION',    'PDF',
   jsonb_build_object('quarter', to_char(CURRENT_DATE, 'YYYY') || 'Q' || to_char(CURRENT_DATE, 'Q')),
   'PENDING', NULL, NULL, 2, now() - interval '5 seconds', NULL);


-- ===================================================================
-- Refresh token（規格書 §7.2.1 refresh_token、FR-01）
-- ===================================================================
-- 只存 SHA-256 雜湊，不存明文：資料庫外流時明文 token 等同一組可直接
-- 使用的登入憑證。這裡的雜湊由固定字串算出來，方便本機重現。
--
-- 三種狀態都有：有效、已撤銷（登出）、已過期。少了後兩者，
-- 「換發時要拒絕哪些 token」這條路徑沒有資料可測。

INSERT INTO refresh_token (user_id, token_hash, issued_at, expires_at, revoked_at, user_agent, ip) VALUES
  (1, encode(sha256(convert_to('dev-refresh-buyer-active', 'UTF8')), 'hex'),
   now() - interval '2 hours', now() + interval '12 days', NULL,
   'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/128.0', '198.51.100.17'),
  (2, encode(sha256(convert_to('dev-refresh-lead-revoked', 'UTF8')), 'hex'),
   now() - interval '3 days', now() + interval '11 days', now() - interval '1 days',
   'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) Safari/17.5', '203.0.113.42'),
  (6, encode(sha256(convert_to('dev-refresh-buyer2-expired', 'UTF8')), 'hex'),
   now() - interval '30 days', now() - interval '16 days', NULL,
   'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0) Safari/604.1', '198.51.100.88');


-- ===================================================================
-- 收尾：把 identity 序列推到目前最大 id 之後
-- ===================================================================
-- 上面所有 INSERT 都自己指定了 id（各表之間要對得起來），
-- 但 GENERATED BY DEFAULT AS IDENTITY 的序列不會因此前進。
-- 不做這一步，應用程式第一次新增資料就會撞主鍵。
--
-- 逐表寫 setval 容易漏，改用系統目錄一次掃完所有有 identity 欄位的表。

DO $$
DECLARE
    rec     RECORD;
    seq     TEXT;
    max_id  BIGINT;
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

        -- is_called = false 時，下一個 nextval 會回傳 setval 給的值本身，
        -- 所以這裡給 max_id + 1，新資料就從最大 id 的下一號開始
        PERFORM setval(seq, max_id + 1, false);

        RAISE NOTICE 'sequence % reset to %', seq, max_id + 1;
    END LOOP;
END $$;
