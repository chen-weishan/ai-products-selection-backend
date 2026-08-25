-- ===================================================================
-- V907：修正共用開發庫裡校準任務的預算池
-- ===================================================================
-- V903 原本把 WEIGHT_CALIBRATION 那筆假資料記在 TRACK_A 池。規格書 L883
-- 明訂 v3.0 合併為三池、校準併入「重試與臨時任務」（RETRY），因此該筆是錯的，
-- 與 AiTaskType.budgetPool() 派生的結果也對不上。
--
-- V903 的檔案已直接改正（同批 V20 提交），全新資料庫插進去就是 RETRY，
-- 本檔在那種情況下是 no-op。但共用開發庫早在 2026-08-23 就套過舊版 V903，
-- 已存在的那一列不會因為檔案被改而更新——已套用的 migration 不會重跑。
-- 本檔專門補這個落差。
--
-- 寫成條件式 UPDATE 而非指定 id：假資料的 id 是硬編的，但條件式版本在
-- 「已經是 RETRY」時影響 0 列，重跑安全。

UPDATE ai_task
SET budget_pool = 'RETRY'
WHERE task_type = 'WEIGHT_CALIBRATION'
  AND budget_pool = 'TRACK_A';

-- import_batch 的 SUCCESS→SUCCEEDED 不必在此處理：V18 自己有 UPDATE，
-- 且版號較小、在共用庫上會先套用。
