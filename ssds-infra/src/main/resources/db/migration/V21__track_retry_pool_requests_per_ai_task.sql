-- v3.0 §FR-07：FULL_ANALYSIS 的首次呼叫屬 TRACK_A，後續重試屬 RETRY。
-- ai_task.request_count 保留總呼叫數；本欄位記錄其中實際由 RETRY 池負擔的次數，
-- 讓服務重啟後仍可正確還原三個每日預算池。

ALTER TABLE ai_task
    ADD COLUMN retry_pool_request_count INT NOT NULL DEFAULT 0
        CHECK (retry_pool_request_count >= 0
               AND retry_pool_request_count <= request_count);

COMMENT ON COLUMN ai_task.retry_pool_request_count IS
    'request_count 中計入 RETRY 預算池的請求數；包括 Agent 內重試與臨時任務呼叫';
