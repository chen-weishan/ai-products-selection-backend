-- ===================================================================
-- V900 開發用假資料（1/3）：主檔與基準資料
-- ===================================================================
--
-- 【這個檔案什麼時候會跑】
-- 只有 spring.profiles.active=dev 時才會載入（見 application-dev.properties
-- 的 spring.flyway.locations）。prod profile 完全看不到 db/dev 這個目錄，
-- 所以假資料不可能跑進正式環境。
--
-- 【為什麼編號從 900 起跳】
-- 讓正式 schema（V1–V5）與假資料在版本序上永遠分得開。日後正式 migration
-- 加到 V6、V7… 也不會撞到，而且 Flyway 依版本號排序，假資料一定最後執行。
--
-- 【為什麼指定 id 而不讓資料庫自動產生】
-- 各表之間要對得起來（這筆分數屬於哪個品項、哪個決策綁哪筆快照），
-- 固定 id 才能在後面的檔案直接引用。代價是插完必須把 identity 序列
-- 推到最大 id 之後，否則之後 JPA 新增資料會撞主鍵 —— 這件事在 V902 收尾。
--
-- 【時間基準】
-- 一律以 CURRENT_DATE 為基準做相對位移，不寫死日期。
-- 寫死的話，這份 seed 過三個月就會變成「一批很久以前的資料」，
-- 趨勢圖是空的、節慶時間窗全部歸零，等於沒有測試價值。
-- ===================================================================


-- ===================================================================
-- 角色（規格書 §2）
-- ===================================================================

INSERT INTO role (id, code, name, description) VALUES
  (1, 'BUYER',      '採購專員',   '日常評估品項、記錄決策'),
  (2, 'BUYER_LEAD', '採購主管',   '覆核決策、調整權重、看報表'),
  (3, 'DATA_ADMIN', '資料管理員', '匯入銷售/評論資料、維護關鍵字'),
  (4, 'SYS_ADMIN',  '系統管理員', '使用者、模型、系統參數'),
  (5, 'VIEWER',     '唯讀觀察者', '只能瀏覽，不能修改');


-- ===================================================================
-- 使用者
-- ===================================================================
-- password_hash 全部是同一組 BCrypt(cost=10)，明碼為 Ssds@2026
-- 這是開發環境專用的共用密碼，正式環境由 SYS_ADMIN 建帳號時另行設定。
-- 刻意用真的 BCrypt 雜湊而不是假字串，這樣登入流程才能真的被測到。

INSERT INTO app_user (id, email, password_hash, display_name, status, failed_attempts) VALUES
  (1, 'buyer@ssds.dev',      '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '林采薇', 'ACTIVE', 0),
  (2, 'lead@ssds.dev',       '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '陳建豪', 'ACTIVE', 0),
  (3, 'dataadmin@ssds.dev',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '黃詩涵', 'ACTIVE', 0),
  (4, 'sysadmin@ssds.dev',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '王紹安', 'ACTIVE', 0),
  (5, 'viewer@ssds.dev',     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '吳靜宜', 'ACTIVE', 0),
  -- 第二位採購專員：多人標記的信心係數（§5.3.2）需要至少兩個標記者才測得出來
  (6, 'buyer2@ssds.dev',     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '張佩琪', 'ACTIVE', 0),
  -- 停用帳號：登入流程要能擋下 DISABLED
  (7, 'disabled@ssds.dev',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '李明德', 'DISABLED', 0);

INSERT INTO user_role (user_id, role_id) VALUES
  (1, 1),           -- 林采薇：BUYER
  (6, 1),           -- 張佩琪：BUYER
  (2, 2),           -- 陳建豪：BUYER_LEAD
  (3, 3),           -- 黃詩涵：DATA_ADMIN
  (4, 4),           -- 王紹安：SYS_ADMIN
  (5, 5),           -- 吳靜宜：VIEWER
  (7, 1);           -- 李明德：BUYER（帳號已停用）


-- ===================================================================
-- 品類（兩層）
-- ===================================================================
-- 粒度刻意設在「零食」「飲品」這一層而非「餅乾」「咖啡」：
-- §5.3.1 的同品類百分位需要每類至少 10 筆樣本，切太細會讓大部分品項
-- 都落入 §5.7 的降級處理，反而看不出正常路徑的行為。

INSERT INTO category (id, parent_id, name, sort_order) VALUES
  (1,  NULL, '食品',   1),
  (2,  NULL, '日用品', 2),
  (3,  NULL, '美妝',   3),
  (4,  NULL, '家電',   4),
  (10, 1, '零食',     1),
  (11, 1, '飲品',     2),
  (12, 1, '生鮮冷凍', 3),
  (20, 2, '清潔用品', 1),
  (21, 2, '紙製品',   2),
  (30, 3, '保養品',   1),
  (31, 3, '彩妝',     2),
  (40, 4, '小家電',   1);


-- ===================================================================
-- 品類前置天數（規格書 FR-17-1）
-- ===================================================================
-- 規格明列：食品（進口）45 天／食品（國產）21 天／日用品 30 天／美妝 35 天。
-- 本表以 category 為主鍵，故進口與國產的差異落在品項層級（零食多為進口，
-- 生鮮冷凍多為國產），此處取該品類的代表值。
-- AC-17-3：同一份資料同時供 FR-16 時效落差使用，不另外維護第二份。

INSERT INTO category_lead_time (category_id, lead_time_days) VALUES
  (10, 45),   -- 零食：以進口為主
  (11, 45),   -- 飲品：以進口為主
  (12, 21),   -- 生鮮冷凍：國產為主
  (20, 30),   -- 清潔用品
  (21, 30),   -- 紙製品
  (30, 35),   -- 保養品
  (31, 35),   -- 彩妝
  (40, 30);   -- 小家電


-- ===================================================================
-- 供應商
-- ===================================================================

INSERT INTO supplier (id, name, contact, phone, note) VALUES
  (1, '晨曦food貿易',   '劉先生', '02-2731-5588', '日韓零食進口，交期穩定約 45 天'),
  (2, '南洋物產',       '陳小姐', '07-336-2201',  '東南亞飲品，最小訂購量偏高'),
  (3, '在地鮮農合作社', '楊經理', '04-2359-7712', '國產生鮮，需冷鏈配送'),
  (4, '潔淨生活用品',   '吳先生', '03-452-8890',  '日用品綜合供應'),
  (5, '美研國際',       '蔡小姐', '02-8768-3344', '韓系美妝代理'),
  (6, '宏晉電器',       '林協理', '02-2299-6677', '小家電，保固由原廠負責');


-- ===================================================================
-- 熱度來源（規格書 §7.2 heat_source、FR-14-2）
-- ===================================================================
-- composite_weight 合計 = 0.35 + 0.30 + 0.15 + 0.20 = 1.00
--
-- 資料狀態刻意做成「不是全部正常」：
--   INSTAGRAM 設為 DEGRADED，用來驗證 §5.3.2 的降級行為 ——
--   其權重歸零後，其餘來源須按比例重新正規化，分數照常產生。
--   全部設 AVAILABLE 的話，這條路徑永遠不會被執行到。
-- Facebook／TikTok／小紅書不建列：附錄 C 已判定無合法程式化管道，
-- 改由 MANUAL 涵蓋。

INSERT INTO heat_source
    (id, source_code, adapter_type, composite_weight, availability, quota_used, quota_limit, last_fetched_at, enabled) VALUES
  (1, 'THREADS',       'REST',   0.350, 'AVAILABLE',  1420, 5000, now() - interval '3 hours',  TRUE),
  (2, 'GOOGLE_TRENDS', 'REST',   0.300, 'AVAILABLE',   680, 2000, now() - interval '5 hours',  TRUE),
  (3, 'INSTAGRAM',     'REST',   0.150, 'DEGRADED',    195,  200, now() - interval '2 days',   TRUE),
  (4, 'MANUAL',        'MANUAL', 0.200, 'AVAILABLE',     0, NULL, now() - interval '1 hours',  TRUE);


-- ===================================================================
-- 節慶檔期（規格書 §7.2 festival_calendar、FR-17-1）
-- ===================================================================
-- LUNAR 類的日期照理應由農曆換算產生（AC-17-1），此處先以已知的
-- 國曆對照值填入，換算模組完成後由排程覆寫。
-- 年度以 CURRENT_DATE 推算，讓這份 seed 跨年後仍然有意義。

INSERT INTO festival_calendar (id, festival_code, festival_name, calendar_type, festival_date, year) VALUES
  (1,  'LUNAR_NEW_YEAR', '農曆年',   'LUNAR', make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int, 2, 17),  EXTRACT(YEAR FROM CURRENT_DATE)::int),
  (2,  'LANTERN',        '元宵',     'LUNAR', make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int, 3, 3),   EXTRACT(YEAR FROM CURRENT_DATE)::int),
  (3,  'TOMB_SWEEPING',  '清明',     'SOLAR', make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int, 4, 4),   EXTRACT(YEAR FROM CURRENT_DATE)::int),
  (4,  'DRAGON_BOAT',    '端午',     'LUNAR', make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int, 6, 19),  EXTRACT(YEAR FROM CURRENT_DATE)::int),
  (5,  'FATHERS_DAY',    '父親節',   'SOLAR', make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int, 8, 8),   EXTRACT(YEAR FROM CURRENT_DATE)::int),
  (6,  'MID_AUTUMN',     '中秋',     'LUNAR', make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int, 9, 25),  EXTRACT(YEAR FROM CURRENT_DATE)::int),
  (7,  'HALLOWEEN',      '萬聖',     'SOLAR', make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int, 10, 31), EXTRACT(YEAR FROM CURRENT_DATE)::int),
  (8,  'CHRISTMAS',      '聖誕',     'SOLAR', make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int, 12, 25), EXTRACT(YEAR FROM CURRENT_DATE)::int),
  (9,  'NEW_YEAR_EVE',   '跨年',     'SOLAR', make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int, 12, 31), EXTRACT(YEAR FROM CURRENT_DATE)::int),
  (10, 'BACK_TO_SCHOOL', '開學季',   'SOLAR', make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int, 9, 1),   EXTRACT(YEAR FROM CURRENT_DATE)::int),
  (11, 'DOUBLE_11',      '雙 11',    'SOLAR', make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int, 11, 11), EXTRACT(YEAR FROM CURRENT_DATE)::int),
  (12, 'DOUBLE_12',      '雙 12',    'SOLAR', make_date(EXTRACT(YEAR FROM CURRENT_DATE)::int, 12, 12), EXTRACT(YEAR FROM CURRENT_DATE)::int);


-- ===================================================================
-- 季節氣候基準（規格書 §7.2 climate_normal、FR-17-2）
-- ===================================================================
-- 台灣北中南三區的月均溫與降雨機率，取自一般氣候常識的近似值。
-- 這是「歷史同期統計」，可以進評分；短期預報不得計入（AC-17-4），
-- 因此本系統沒有任何預報資料表。

INSERT INTO climate_normal (region_code, month, avg_temp, rain_probability) VALUES
  ('TW_NORTH',  1, 16.1, 42.0), ('TW_NORTH',  2, 16.5, 40.0), ('TW_NORTH',  3, 18.5, 38.0),
  ('TW_NORTH',  4, 22.0, 35.0), ('TW_NORTH',  5, 25.4, 40.0), ('TW_NORTH',  6, 28.3, 45.0),
  ('TW_NORTH',  7, 30.1, 38.0), ('TW_NORTH',  8, 29.6, 44.0), ('TW_NORTH',  9, 27.7, 41.0),
  ('TW_NORTH', 10, 24.5, 36.0), ('TW_NORTH', 11, 21.5, 34.0), ('TW_NORTH', 12, 17.9, 38.0),
  ('TW_CENTRAL',  1, 16.9, 18.0), ('TW_CENTRAL',  2, 17.8, 20.0), ('TW_CENTRAL',  3, 20.6, 24.0),
  ('TW_CENTRAL',  4, 24.0, 30.0), ('TW_CENTRAL',  5, 26.7, 42.0), ('TW_CENTRAL',  6, 28.3, 52.0),
  ('TW_CENTRAL',  7, 29.0, 48.0), ('TW_CENTRAL',  8, 28.6, 50.0), ('TW_CENTRAL',  9, 27.9, 36.0),
  ('TW_CENTRAL', 10, 25.4, 16.0), ('TW_CENTRAL', 11, 22.2, 12.0), ('TW_CENTRAL', 12, 18.5, 14.0),
  ('TW_SOUTH',  1, 19.3, 10.0), ('TW_SOUTH',  2, 20.4, 12.0), ('TW_SOUTH',  3, 23.1, 16.0),
  ('TW_SOUTH',  4, 26.0, 22.0), ('TW_SOUTH',  5, 28.0, 40.0), ('TW_SOUTH',  6, 28.7, 58.0),
  ('TW_SOUTH',  7, 29.2, 56.0), ('TW_SOUTH',  8, 28.7, 60.0), ('TW_SOUTH',  9, 28.3, 42.0),
  ('TW_SOUTH', 10, 26.7, 16.0), ('TW_SOUTH', 11, 24.0,  8.0), ('TW_SOUTH', 12, 20.6,  8.0);
