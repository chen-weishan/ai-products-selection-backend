-- ===================================================================
-- DRAFT - Agent 6: SourcingScoutAgent structured output
--
-- This file intentionally lives outside db/migration. Flyway must not run it
-- until the matching Agent 6 application code is implemented and reviewed.
-- Move it into db/migration only when Agent 6 is ready to be deployed.
-- ===================================================================

ALTER TABLE sourcing_candidate
    ADD COLUMN opportunity_signals JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN risk_signals JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN trend_interpretation_id BIGINT REFERENCES trend_interpretation (id),
    ADD COLUMN model VARCHAR(80),
    ADD COLUMN prompt_version VARCHAR(20),
    ADD COLUMN report_generated_at TIMESTAMPTZ;

ALTER TABLE sourcing_candidate
    ADD CONSTRAINT ck_sourcing_opportunity_signals_array CHECK (
        jsonb_typeof(opportunity_signals) = 'array'
    ),
    ADD CONSTRAINT ck_sourcing_risk_signals_array CHECK (
        jsonb_typeof(risk_signals) = 'array'
    );

COMMENT ON COLUMN sourcing_candidate.opportunity_signals IS
    'Agent 6 探索報告的機會訊號陣列，供 S-17 結構化呈現';
COMMENT ON COLUMN sourcing_candidate.risk_signals IS
    'Agent 6 探索報告的風險訊號陣列，供 S-17 結構化呈現';
COMMENT ON COLUMN sourcing_candidate.trend_interpretation_id IS
    '本次時效落差引用的 Agent 5 趨勢判定，保留可追溯關聯';

CREATE INDEX idx_sourcing_trend_interpretation
    ON sourcing_candidate (trend_interpretation_id);

