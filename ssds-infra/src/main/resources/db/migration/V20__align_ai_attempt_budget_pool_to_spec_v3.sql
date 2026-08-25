-- ===================================================================
-- V20：ai_attempt.budget_pool 值域對齊規格書 §7.2.7 L2930
-- ===================================================================
-- V11 用 A_TRACK／B_TRACK／RESERVE，V17 把 ai_task.budget_pool 對齊成規格書的
-- TRACK_A／TRACK_B／RETRY（V17 L408-416），但沒動 ai_attempt——它是規格書未
-- 提及的實作擴充，V17 依「未列的表一律保留」裁決整張跳過。
-- 結果同一個概念在同一個庫裡兩套字：ai_task 寫 TRACK_A，ai_attempt 寫 A_TRACK，
-- 兩表要 JOIN 統計配額用量時必須先轉換，是 §6.7.3 每日配額稽核的直接障礙。
-- （Vincent 2026-08-25 定案）
--
-- 對映：A_TRACK→TRACK_A、B_TRACK→TRACK_B、RESERVE→RETRY。
-- 第三個池的語意兩邊一致（FR-07 L879「重試與臨時任務」10%），只是名稱不同。
--
-- 【Java 端】ai_attempt 沒有 Entity，本檔無須搭配 Entity 變更。
-- 但同批提交另有一項相關修正：AiTaskType.BudgetPool 的 CALIBRATION → RETRY。
-- 規格書 L883 明訂 v3.0 合併為三池、校準併入「重試與臨時任務」池，
-- 「校準獨立計費」是被 v3.0 推翻的 v2.0 說法。

ALTER TABLE ai_attempt
    DROP CONSTRAINT IF EXISTS ai_attempt_budget_pool_check,
    DROP CONSTRAINT IF EXISTS ck_ai_attempt_budget_pool;

UPDATE ai_attempt
SET budget_pool = CASE budget_pool
                      WHEN 'A_TRACK' THEN 'TRACK_A'
                      WHEN 'B_TRACK' THEN 'TRACK_B'
                      WHEN 'RESERVE' THEN 'RETRY' END
WHERE budget_pool IN ('A_TRACK', 'B_TRACK', 'RESERVE');

ALTER TABLE ai_attempt
    ADD CONSTRAINT ck_ai_attempt_budget_pool
        CHECK (budget_pool IN ('TRACK_A', 'TRACK_B', 'RETRY'));

-- V11 的欄位註解仍寫著舊名，會誤導讀 schema 的人
COMMENT ON COLUMN ai_attempt.budget_pool IS
    'TRACK_A=批次評分 70%、TRACK_B=尋源 20%、RETRY=重試與臨時任務 10%（含季度校準，FR-07）';
