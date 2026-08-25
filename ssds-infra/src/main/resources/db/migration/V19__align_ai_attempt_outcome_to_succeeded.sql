-- ===================================================================
-- V19：ai_attempt.outcome 的 SUCCESS 改為 SUCCEEDED
-- ===================================================================
-- ai_attempt 是規格書未提及的實作擴充（V11 建立，支援 §6.7.3-§6.7.4 的重試與
-- 配額稽核），V17 依「規格書未列的表一律保留」裁決留下（V17 檔頭 L17）。
-- 因此本檔不是對齊規格書，而是**內部一致性**：同一個資料庫裡，ai_task、
-- ai_task_item、import_batch 的成功狀態都叫 SUCCEEDED（V17、V18），
-- 只剩 ai_attempt 叫 SUCCESS，讀 SQL 的人必須記住哪張表用哪個字。
-- （Vincent 2026-08-25 定案）
--
-- outcome 與那三張表的 status 語意不同：它記的是**單次 LLM 請求**的結果，
-- 其餘六個值（RATE_LIMITED／SCHEMA_INVALID／TIMEOUT／HTTP_ERROR／
-- NETWORK_ERROR／CANCELLED）都是失敗原因的分類，不對應任何任務狀態。
-- 因此只改字面，不併入 TaskStatus，日後若建 Entity 應為獨立 enum。
--
-- 【Java 端】ai_attempt 目前沒有對應的 Entity，本檔無須搭配 Java 變更。

ALTER TABLE ai_attempt
    DROP CONSTRAINT IF EXISTS ai_attempt_outcome_check,
    DROP CONSTRAINT IF EXISTS ck_ai_attempt_outcome;

UPDATE ai_attempt SET outcome = 'SUCCEEDED' WHERE outcome = 'SUCCESS';

ALTER TABLE ai_attempt
    ADD CONSTRAINT ck_ai_attempt_outcome
        CHECK (outcome IN ('SUCCEEDED', 'RATE_LIMITED', 'SCHEMA_INVALID',
                           'TIMEOUT', 'HTTP_ERROR', 'NETWORK_ERROR',
                           'CANCELLED'));

COMMENT ON COLUMN ai_attempt.outcome IS
    '單次 LLM 請求的結果。SUCCEEDED 之外皆為失敗原因分類（§6.7.3 退避重試依此判斷）';
