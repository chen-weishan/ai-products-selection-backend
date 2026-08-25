-- ===================================================================
-- V18：import_batch.status 值域對齊規格書 §7.2.7 的 SUCCEEDED
-- ===================================================================
-- V17 已把 ai_task／ai_task_item 的 SUCCESS 改為規格書的 SUCCEEDED
-- （V17 L424-433、L476-482），但漏了 import_batch——因為規格書 §7.2.11
-- L3115 的 import_batch 只列欄位名、沒列 status 值域，V17 當時採「規格沒寫
-- 就不動」。結果同一個 Java enum（TaskStatus）同時對應兩套值域，寫入必爆。
--
-- 【裁決】三張表共用 TaskStatus，值域一律以 §7.2.7 L2931 為準。
-- 規格書未明文規定 import_batch 的 status 值域，此為設計決定（Vincent 2026-08-25 定案）。
-- 選擇沿用而非另開 ImportStatus：import_batch 與 ai_task 的六個值完全相同
-- （含 FR-09 需要的 PARTIAL），沒有值域差異可支撐拆分。
--
-- 【本檔需搭配的 Java 端變更】（同批提交，缺一則 ddl-auto=validate 之外的
-- 執行期寫入會被 CHECK 擋下）：
--   1. TaskStatus：SUCCESS → SUCCEEDED
--   2. 新增 TaskItemStatus（PENDING／SUCCEEDED／FAILED／SKIPPED_CACHE／SKIPPED_QUOTA），
--      AiTaskItem.status 與 AiTaskItemRepository 兩個方法改用之
--   3. ImportDataType：SALES_RECORD／PRODUCT_REVIEW／MEMBER_PROFILE／PRODUCT_MASTER
--      → SALES／REVIEW／AUDIENCE／PRODUCT（DB 側 V17 L867 已完成，本檔無對應 SQL）
--
-- 【方言】沿用 V1 檔頭規則：ENUM→VARCHAR+CHECK。

ALTER TABLE import_batch
    DROP CONSTRAINT IF EXISTS import_batch_status_check,
    DROP CONSTRAINT IF EXISTS ck_import_batch_status;

UPDATE import_batch SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';

ALTER TABLE import_batch
    ADD CONSTRAINT ck_import_batch_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED'));

COMMENT ON COLUMN import_batch.status IS
    '規格書 §7.2.7 值域；PARTIAL 為 FR-09 部分成功時仍寫入正確列的狀態';
