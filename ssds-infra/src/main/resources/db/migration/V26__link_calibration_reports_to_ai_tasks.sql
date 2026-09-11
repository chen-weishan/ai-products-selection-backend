-- FR-07：Agent 7 權重校準須出現在 AI 任務中心，逐項紀錄需指向正式校準報告。
ALTER TABLE ai_task_item
    ADD COLUMN calibration_report_id BIGINT
        REFERENCES calibration_report (id) ON DELETE CASCADE;

CREATE INDEX idx_ai_task_item_calibration_report
    ON ai_task_item (calibration_report_id);

COMMENT ON COLUMN ai_task_item.calibration_report_id IS
    'WEIGHT_CALIBRATION 任務的目標 calibration_report；與 product_id、keyword_id 擇一';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ssds_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON ai_task_item TO ssds_app;
    END IF;
END $$;
