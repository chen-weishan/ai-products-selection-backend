-- ===================================================================
-- DRAFT - Agent 7: WeightCalibrationAgent output and review decision
--
-- This file intentionally lives outside db/migration. Flyway must not run it
-- until the matching Agent 7 application code is implemented and reviewed.
-- Move it into db/migration only when Agent 7 is ready to be deployed.
-- ===================================================================

ALTER TABLE calibration_report
    ADD COLUMN adjustment_advice JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN attention_notes JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN accepted_adjustments JSONB,
    ADD COLUMN resulting_weight_version_id BIGINT REFERENCES weight_version (id),
    ADD COLUMN model VARCHAR(80),
    ADD COLUMN prompt_version VARCHAR(20),
    ADD COLUMN interpreted_at TIMESTAMPTZ;

ALTER TABLE calibration_report
    ADD CONSTRAINT ck_calibration_adjustment_advice_array CHECK (
        jsonb_typeof(adjustment_advice) = 'array'
    ),
    ADD CONSTRAINT ck_calibration_attention_notes_array CHECK (
        jsonb_typeof(attention_notes) = 'array'
    ),
    ADD CONSTRAINT ck_calibration_accepted_adjustments_array CHECK (
        accepted_adjustments IS NULL OR jsonb_typeof(accepted_adjustments) = 'array'
    ),
    ADD CONSTRAINT ck_calibration_resulting_version CHECK (
        resulting_weight_version_id IS NULL OR status IN ('APPROVED', 'PARTIAL')
    );

COMMENT ON COLUMN calibration_report.adjustment_advice IS
    'Agent 7 對統計模組建議的逐項文字解讀；數值仍只能來自 regression_result';
COMMENT ON COLUMN calibration_report.attention_notes IS
    'Agent 7 需注意事項，例如情境覆寫集中品類或樣本偏差';
COMMENT ON COLUMN calibration_report.accepted_adjustments IS
    'BUYER_LEAD 部分採納時勾選的調整項目；NULL 表尚未審核或非部分採納';
COMMENT ON COLUMN calibration_report.resulting_weight_version_id IS
    '核准或部分採納後所建立的權重草稿版本';

CREATE INDEX idx_calibration_resulting_weight_version
    ON calibration_report (resulting_weight_version_id);

