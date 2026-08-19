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

INSERT INTO item_festival_affinity (product_id, festival_code, affinity) VALUES
  (107, 'MID_AUTUMN',     1.00),
  (107, 'DOUBLE_11',      0.20),
  (101, 'MID_AUTUMN',     0.45),
  (101, 'CHRISTMAS',      0.55),
  (101, 'LUNAR_NEW_YEAR', 0.60),
  (102, 'LUNAR_NEW_YEAR', 0.75),
  (102, 'LANTERN',        0.40),
  (106, 'FATHERS_DAY',    0.65),
  (106, 'MID_AUTUMN',     0.50),
  (110, 'LUNAR_NEW_YEAR', 0.70),   -- 年前大掃除
  (109, 'LUNAR_NEW_YEAR', 0.65),
  (113, 'DOUBLE_11',      0.80),
  (113, 'CHRISTMAS',      0.60),
  (112, 'DOUBLE_11',      0.75),
  (114, 'DOUBLE_11',      0.85),
  (114, 'DOUBLE_12',      0.70),
  (117, 'BACK_TO_SCHOOL', 0.55);


-- ===================================================================
-- 匯入批次與錯誤（規格書 §7.2 import_batch / import_error、FR-09）
-- ===================================================================
-- 三種結果都要有：全成功、部分成功（PARTIAL）、全失敗。
-- FR-09 明訂「部分成功時仍寫入正確列」，PARTIAL 這個狀態沒有資料就測不到。

INSERT INTO import_batch (id, data_type, file_name, total_rows, success_rows, fail_rows,
                          status, created_by, created_at) VALUES
  (1, 'SALES_RECORD',   '2026H1_團購銷售明細.xlsx',   4820, 4820,   0, 'SUCCESS', 3, now() - interval '30 days'),
  (2, 'PRODUCT_REVIEW', '蝦皮評論匯出_20260701.csv',   1260, 1244,  16, 'PARTIAL', 3, now() - interval '18 days'),
  (3, 'MEMBER_PROFILE', '會員輪廓_2026Q2.csv',          890,  890,   0, 'SUCCESS', 3, now() - interval '12 days'),
  (4, 'PRODUCT_MASTER', '新品清單_0805.xlsx',            42,    0,  42, 'FAILED',  3, now() - interval '6 days'),
  (5, 'SALES_RECORD',   '2026Q3_團購銷售明細.xlsx',    1150, 1150,   0, 'SUCCESS', 3, now() - interval '2 days');

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

INSERT INTO sales_record (order_date, product_id, product_name_raw, category_id,
                          price, qty, impression, audience_tag, import_batch_id)
SELECT CURRENT_DATE - d,
       p.id,
       p.name,
       p.category_id,
       p.suggested_price,
       -- 以品項 id 與日期做出可重現但看起來隨機的銷量
       GREATEST(1, (18 + (p.id % 7) * 4 + (sin((d + p.id) / 6.0) * 9)::int)),
       CASE WHEN p.id % 5 = 0 THEN NULL          -- 兩成品項沒有曝光數
            ELSE GREATEST(50, (620 + (p.id % 11) * 45 + (cos(d / 8.0) * 120)::int)) END,
       p.target_audience,
       CASE WHEN d > 60 THEN 1 ELSE 5 END
FROM product p
         CROSS JOIN generate_series(0, 179, 3) AS d      -- 每 3 天一筆，避免資料量過大
WHERE p.track_type = 'A'
  AND p.status IN ('LISTED', 'ADOPTED')
  AND p.suggested_price IS NOT NULL;

-- 比對不到品項的列：product_id 為 NULL 但保留原始名稱，供後續人工對應
INSERT INTO sales_record (order_date, product_id, product_name_raw, category_id,
                          price, qty, impression, audience_tag, import_batch_id) VALUES
  (CURRENT_DATE - 40, NULL, '日式抹茶餅乾(舊品名)', 10, 119.00, 32, 540, 'F25_34', 1),
  (CURRENT_DATE - 38, NULL, '黑糖珍珠奶茶包 15入',   11,  89.00, 58, 810, 'F18_24', 1),
  (CURRENT_DATE - 22, NULL, '未知品項A',             NULL, 250.00, 4, NULL, NULL,    1);


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

-- v2.0：分析輸出是風險主題分類，不再是加分用的情感分數（§5.2.2）
INSERT INTO review_analysis (review_id, sentiment, aspects, key_phrase, model, analyzed_at)
SELECT r.id,
       CASE WHEN r.rating >= 4 THEN 'POSITIVE'
            WHEN r.rating >= 3 THEN 'NEUTRAL'
            ELSE 'NEGATIVE' END,
       CASE WHEN r.content LIKE '%退冰%' OR r.content LIKE '%濕透%' OR r.content LIKE '%壓痕%'
                                              THEN '物流破損'
            WHEN r.content LIKE '%結塊%' OR r.content LIKE '%香精%' OR r.content LIKE '%口感%'
                                              THEN '品質'
            WHEN r.content LIKE '%配送%'      THEN '物流延遲'
            WHEN r.rating >= 4                THEN '風味,包裝'
            ELSE '其他' END,
       CASE WHEN r.content LIKE '%抹茶%' THEN '抹茶不苦澀'
            WHEN r.content LIKE '%退冰%' THEN '冷鏈失溫'
            WHEN r.content LIKE '%顯色%' THEN '顯色不乾'
            ELSE NULL END,
       'meta-llama/llama-3.3-70b-instruct:free',
       now() - interval '2 days'
FROM product_review r;


-- ===================================================================
-- AI 任務（規格書 §7.2 ai_task / ai_task_item、FR-07）
-- ===================================================================
-- 三個預算池都要有任務，AC-07-2「B 軌耗盡不影響 A 軌」才驗證得了。
-- total_cost_usd 全為 0：§3.2 一律使用免費模型。

INSERT INTO ai_task (id, task_type, status, total_count, success_count, fail_count,
                     total_cost_usd, created_by, started_at, finished_at) VALUES
  (1, 'SCENE_CLASSIFY',     'SUCCESS', 17, 17,  0, 0, 3, now() - interval '2 days' - interval '40 minutes', now() - interval '2 days' - interval '31 minutes'),
  (2, 'REVIEW_RISK',        'SUCCESS', 19, 19,  0, 0, 3, now() - interval '2 days' - interval '30 minutes', now() - interval '2 days' - interval '18 minutes'),
  (3, 'SELLING_POINT',      'PARTIAL', 17, 14,  3, 0, 3, now() - interval '2 days' - interval '18 minutes', now() - interval '2 days' - interval '4 minutes'),
  (4, 'RECOMMENDATION',     'RUNNING', 17,  9,  0, 0, 1, now() - interval '12 minutes', NULL),
  (5, 'SOURCING_SCOUT',     'SUCCESS',  4,  4,  0, 0, 1, now() - interval '4 days', now() - interval '4 days' + interval '6 minutes'),
  (6, 'WEIGHT_CALIBRATION', 'SUCCESS',  1,  1,  0, 0, 2, now() - interval '5 days', now() - interval '5 days' + interval '3 minutes'),
  (7, 'TREND_INTERPRET',    'FAILED',   6,  0,  6, 0, 3, now() - interval '1 days', now() - interval '1 days' + interval '2 minutes');

INSERT INTO ai_task_item (task_id, product_id, status, error_message, raw_response, duration_ms) VALUES
  (3, 112, 'FAILED', 'JSON Schema 驗證失敗：points[0] 缺少必要欄位 evidence',
   '{"points":[{"title":"保濕感明顯"}],"summary":"評論偏正面"}', 4210),
  (3, 114, 'FAILED', 'JSON Schema 驗證失敗：回應非合法 JSON',
   '這款氣炸鍋的賣點包括：1. 容量適中 2. 好清洗（模型直接回了純文字）', 3880),
  (3, 118, 'FAILED', '品項資料不足：缺少成本與售價，無法產生賣點',
   NULL, 120),
  (3, 101, 'SUCCESS', NULL, NULL, 5120),
  (3, 104, 'SUCCESS', NULL, NULL, 6340),
  (3, 113, 'SUCCESS', NULL, NULL, 4890),
  -- 整批失敗：上游服務不可用。FR-07 的「重跑失敗項」就是拿這種資料測
  (7, 101, 'FAILED', 'OpenRouter 回應 429 Too Many Requests，已達免費模型每日配額', NULL, 890),
  (7, 107, 'FAILED', 'OpenRouter 回應 429 Too Many Requests，已達免費模型每日配額', NULL, 760),
  (7, 117, 'FAILED', 'OpenRouter 回應 429 Too Many Requests，已達免費模型每日配額', NULL, 810),
  -- 非品項層級的任務：product_id 為 NULL
  (6, NULL, 'SUCCESS', NULL, NULL, 18240);


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

INSERT INTO decision_record (id, product_id, score_id, decision, followed_ai, ai_insight_id,
                             first_order_qty, expected_list_date, reason, decided_by, decided_at) VALUES
  (1, 101, 1, 'ADOPT',  TRUE,
   (SELECT id FROM ai_insight WHERE product_id = 101 AND insight_type = 'RECOMMENDATION' AND is_current),
   600, CURRENT_DATE - 21, '熱度與毛利都在中上，採納 AI 建議數量', 1, now() - interval '35 days'),

  (2, 105, 5, 'ADOPT',  TRUE,  NULL,
   800, CURRENT_DATE - 45, '常態補貨，轉換率穩定', 1, now() - interval '55 days'),

  (3, 104, 4, 'WATCH',  TRUE,
   (SELECT id FROM ai_insight WHERE product_id = 104 AND insight_type = 'RECOMMENDATION' AND is_current),
   NULL, NULL, '冷鏈負評集中，先解決配送再談開團', 1, now() - interval '2 days'),

  -- 未採納 AI 建議：AC-11-2 要求理由必填
  (4, 107, 7, 'ADOPT',  FALSE, NULL,
   400, CURRENT_DATE + 14,
   'AI 建議首批 250，但中秋禮盒去年同期兩天完售且補單來不及，改抓 400', 2, now() - interval '10 days'),

  (5, 116, 16, 'REJECT', FALSE, NULL,
   NULL, NULL,
   'AI 建議觀察，但同品類已有兩支開團中的奶茶品項，客層完全重疊，直接淘汰避免內耗', 2, now() - interval '9 days'),

  (6, 111, 11, 'ADOPT',  TRUE,  NULL,
   300, CURRENT_DATE - 30, '轉換率表現好，客群明確', 1, now() - interval '40 days'),

  (7, 109, 9, 'ADOPT',   TRUE,  NULL,
   500, CURRENT_DATE - 60, '日用品剛需，補貨型', 1, now() - interval '70 days'),

  (8, 110, 10, 'ADOPT',  TRUE,  NULL,
   200, CURRENT_DATE - 90, '轉換率同品類最高', 1, now() - interval '100 days'),

  -- 這筆刻意設成「未採納 AI 且情境被覆寫」，而且它有結案結果。
  -- FR-11-3 的覆寫率與 AI 採納率只統計「已回填」的樣本，
  -- 若已回填的都是採納且未覆寫，那兩個指標會固定顯示 0% 與 100%，
  -- 組員開發那個畫面時會誤以為算錯了。
  (9, 102, 2, 'ADOPT',   FALSE, NULL,
   350, CURRENT_DATE - 12,
   'AI 依熱度斜率判為話題爆款型並建議首批 550，但草莓年糕是明確的冬季品項，'
   || '去年同期熱度曲線幾乎一樣，屬季節導向；已改判情境並下修首批至 350',
   6, now() - interval '20 days'),

  (10, 113, 13, 'WATCH', TRUE,  NULL,
   NULL, NULL, '熱度剛起，再觀察兩週確認不是短期波動', 6, now() - interval '3 days');

-- 開團快照：AC-11-6 要求建立後不因權重改版而變動，
-- 因此因子值以 JSON 就地凍結，不靠 join 回 score_factor。
INSERT INTO campaign_snapshot (decision_id, score, grade, bonus_subtotal, penalty_subtotal,
                               weight_version_id, scene_type, scene_overridden,
                               factor_values, source_availability, created_at)
SELECT d.id,
       s.final_score,
       s.grade,
       s.bonus_subtotal,
       s.penalty_subtotal,
       s.weight_version_id,
       s.scene_type,
       -- 品項 107、104、102 的情境曾被人工覆寫（見 V902 的 scene_classification_log）
       d.product_id IN (107, 104, 102),
       (SELECT jsonb_object_agg(f.factor_code,
                                jsonb_build_object('raw',    f.raw_value,
                                                   'norm',   f.normalized_value,
                                                   'weight', f.weight,
                                                   'penalty', f.penalty_value))
        FROM score_factor f WHERE f.score_id = s.id),
       '{"THREADS":"AVAILABLE","GOOGLE_TRENDS":"AVAILABLE","INSTAGRAM":"DEGRADED","MANUAL":"AVAILABLE"}'::jsonb,
       d.decided_at
FROM decision_record d
         JOIN product_score s ON s.id = d.score_id;


-- ===================================================================
-- 結案回填（規格書 §7.2 decision_feedback / campaign_result、FR-11-2）
-- ===================================================================
-- 六筆已回填、四筆未回填。其中決策 4（中秋蛋黃酥）與決策 9（草莓年糕）
-- 已過預計上架日超過 7 天仍未回填 —— 這正是 AC-11-3 儀表板待辦區
-- 要抓出來的兩筆。
--
-- 這批資料同時是 FR-15 統計迴歸的標籤。六筆遠不及 AC-15-1 要求的 200 筆，
-- 所以校準報告的 status 只能是 PENDING、畫面必須顯示效度警示 ——
-- 這個「樣本不足」的狀態本身就是要被測到的狀態，不是資料沒造完。

INSERT INTO campaign_result (decision_id, actual_qty, sellout_status, return_rate,
                             realized_margin, post_note_code, post_note_text, filled_by, filled_at) VALUES
  (1, 742, 'EARLY_SELLOUT',  1.20, 50.10, 'FASTER_THAN_EXPECTED', '開團第 2 天就補了一次單',        1, now() - interval '14 days'),
  (2, 810, 'ON_TIME',        0.80, 59.40, NULL,                    NULL,                              1, now() - interval '30 days'),
  (6, 268, 'BELOW_TARGET',   2.10, 48.90, 'HEAT_FADED',            '開團時討論度已經比評估時低',      1, now() - interval '18 days'),
  (7, 512, 'ON_TIME',        1.50, 50.20, NULL,                    NULL,                              1, now() - interval '45 days'),
  (8, 196, 'ON_TIME',        0.60, 41.10, NULL,                    NULL,                              1, now() - interval '75 days'),
  -- 滯銷案例：迴歸需要低分低銷量的樣本，全是成功案例的話相關係數毫無意義
  (9,  88, 'SLOW',           4.30, 44.20, 'QUALITY_ISSUE',         '收到反映外包裝受潮，退貨偏多',    6, now() - interval '5 days');

INSERT INTO decision_feedback (decision_id, actual_qty_sold, actual_cvr, sellout_days,
                               return_rate, filled_by, filled_at) VALUES
  (1, 742, 0.0512,  2, 0.0120, 1, now() - interval '14 days'),
  (2, 810, 0.0488, 11, 0.0080, 1, now() - interval '30 days'),
  (6, 268, 0.0231, 21, 0.0210, 1, now() - interval '18 days'),
  (7, 512, 0.0602, 14, 0.0150, 1, now() - interval '45 days'),
  (8, 196, 0.0705, 12, 0.0060, 1, now() - interval '75 days'),
  (9,  88, 0.0102, 30, 0.0430, 6, now() - interval '5 days');


-- ===================================================================
-- B 軌尋源候選（規格書 §7.2 sourcing_candidate、FR-16-2）
-- ===================================================================
-- time_gap_days 必須等於 estimated_lifespan_days − lead_time_days
-- （資料庫端有 CHECK 約束，寫錯會直接插不進去）。
-- lead_time_days 取自 category_lead_time，符合 AC-17-3 的單一來源原則。
--
-- 四筆分別落在時效落差的三個區段：
--   > +14 天  可行，正常排序
--   0 ～ +14  高風險，需加速尋源
--   < 0       淘汰（AC-16-4：自動標記且不可加入清單）

INSERT INTO sourcing_candidate (keyword_id, category_id, heat_stage, stage_weeks,
                                estimated_lifespan_days, lead_time_days, time_gap_days,
                                status, scout_report) VALUES
  -- 上升期 → 壽命 56 天；零食前置期 45 天 → 落差 +11，需加速
  (20, 10, 'RISING',   1, 56, 45, 11, 'URGENT',
   '小紅書近兩週出現大量開箱，台灣尚無代理。已找到兩家韓國中盤有現貨，但最快出貨仍需 6 週；建議同步詢問國內代工可行性。機會訊號：話題新鮮度高、單價帶適合團購。風險訊號：品項為冷藏甜點，夏季配送成本高。'),

  -- 高原期第 3 週 → 壽命 35 天；零食前置期 45 天 → 落差 −10，自動淘汰
  (21, 10, 'PLATEAU',  3, 35, 45, -10, 'REJECTED',
   '熱度已在高原期第三週，依 §5.8 推估剩餘壽命 35 天，而零食類進口前置期 45 天。時效落差為負，來不及在熱度消退前上架。保留紀錄供下次同關鍵字出現時參考。'),

  -- 上升期 → 壽命 56 天；小家電前置期 30 天 → 落差 +26，可行
  (22, 40, 'RISING',   2, 56, 30, 26, 'SOURCING',
   '夏季小物，討論穩定成長且尚未見頂。已聯繫三家供應商，其中一家可提供 OEM 並接受 500 起訂。機會訊號：時效落差充裕、可做客製印刷。風險訊號：同類商品去年已有品牌操作過，需確認差異點。'),

  -- 衰退期 → 壽命 17 天；美妝前置期 35 天 → 落差 −18，淘汰
  (23, 30, 'DECLINING', 5, 17, 35, -18, 'REJECTED',
   '熱度已進入衰退期，剩餘壽命推估 17 天，美妝類前置期 35 天。時效落差為負，不可加入尋源清單。');


-- ===================================================================
-- 權重校準報告（規格書 §7.2 calibration_report、FR-15）
-- ===================================================================
-- 樣本數 6，遠低於 AC-15-1 的 200 筆門檻 → 畫面須顯示不可關閉的效度警示，
-- 且 status 停在 PENDING（AC-15-3：須經 BUYER_LEAD 核准才產生新版本）。
--
-- regression_result 是統計模組的產出，ai_interpretation 是 AI 的解讀。
-- AC-15-2：建議權重只能來自前者，AI 不得自行產生數值 ——
-- 所以 suggestedWeight 這些數字全部落在 regression_result 裡面。

INSERT INTO calibration_report (quarter, sample_size, regression_result, ai_interpretation,
                                backtest_result, status, reviewed_by, reviewed_at, created_at) VALUES
  (to_char(CURRENT_DATE, 'YYYY') || 'Q' || to_char(CURRENT_DATE, 'Q'), 6,
   '{"method":"pearson","factors":[{"code":"HEAT_SLOPE","correlation":0.71,"currentWeight":0.55,"suggestedWeight":0.42,"pValue":0.11},{"code":"HEAT_VOLUME","correlation":0.68,"currentWeight":0.00,"suggestedWeight":0.13,"pValue":0.14},{"code":"MARGIN","correlation":0.22,"currentWeight":0.10,"suggestedWeight":0.10,"pValue":0.67},{"code":"CONVERSION","correlation":0.58,"currentWeight":0.10,"suggestedWeight":0.10,"pValue":0.23},{"code":"FESTIVAL","correlation":0.34,"currentWeight":0.15,"suggestedWeight":0.15,"pValue":0.51},{"code":"CLIMATE","correlation":0.19,"currentWeight":0.10,"suggestedWeight":0.10,"pValue":0.72}],"note":"sample_size=6，所有 p 值均未達 0.05，本結果不具統計顯著性"}'::jsonb,

   '樣本數僅 6 筆，任何一筆的變動都會顯著改變相關係數，因此以下觀察僅供方向參考，不足以支持權重調整。'
   || E'\n\n'
   || '值得注意的是 heat_volume 目前未分配權重，但其與實際銷量的相關係數（0.68）與熱度斜率（0.71）相當。'
   || '這與 §5.2.1 對該因子的設計意圖一致：斜率高但基數小的品項（「3 則變 9 則」）會被斜率單獨誤判為爆款，'
   || '量級因子正是用來擋這種情況。建議在樣本累積至 200 筆後優先驗證這一項。'
   || E'\n\n'
   || '另需留意：情境覆寫集中於「食品／零食」品類（6 筆決策中有 2 筆覆寫，皆為該品類），'
   || '若覆寫是系統性的，代表 SceneClassifierAgent 對節慶型品項的判定規則需要調整，'
   || '而不是權重的問題。',

   '{"backtests":[{"scheme":"EQUAL_WEIGHT","correlation":0.41,"gradeAHitRate":0.50},{"scheme":"CURRENT_V2","correlation":0.63,"gradeAHitRate":0.67},{"scheme":"SUGGESTED_V3","correlation":0.69,"gradeAHitRate":0.67}],"note":"回測樣本同為 6 筆，差異落在雜訊範圍內"}'::jsonb,

   'PENDING', NULL, NULL, now() - interval '5 days');


-- ===================================================================
-- 稽核紀錄（規格書 §7.2 audit_log）
-- ===================================================================

INSERT INTO audit_log (user_id, action, entity_type, entity_id, before_json, after_json, ip, created_at) VALUES
  (2, 'APPROVE', 'WeightVersion', 2,
   '{"status":"DRAFT","effectiveFrom":null}'::jsonb,
   -- 用 jsonb_build_object 而不是字串串接：::jsonb 的優先序高於 ||，
   -- 寫成 '...' || x || '"}'::jsonb 會先把 '"}' 當成 JSON 去解析而報錯
   jsonb_build_object('status', 'ACTIVE', 'effectiveFrom', (CURRENT_DATE - 60)::text),
   '203.0.113.42', now() - interval '62 days'),
  (2, 'RETIRE',  'WeightVersion', 1,
   '{"status":"ACTIVE"}'::jsonb, '{"status":"RETIRED"}'::jsonb,
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
