CREATE TABLE runtime_setting (
    setting_key VARCHAR(96) PRIMARY KEY,
    group_name VARCHAR(32) NOT NULL,
    value_json JSONB NOT NULL,
    updated_by BIGINT REFERENCES app_user(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_runtime_setting_value_json CHECK (jsonb_typeof(value_json) IS NOT NULL)
);

CREATE INDEX idx_runtime_setting_group ON runtime_setting (group_name, setting_key);

COMMENT ON TABLE runtime_setting IS
    'S-14 runtime-overridable settings. Secrets and infrastructure properties are intentionally excluded.';

ALTER TABLE runtime_setting ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ssds_app') THEN
        RAISE NOTICE 'V35: role ssds_app does not exist; skipping grants and policy';
        RETURN;
    END IF;

    GRANT SELECT, INSERT, UPDATE, DELETE ON public.runtime_setting TO ssds_app;
    CREATE POLICY p_ssds_app_rw ON public.runtime_setting
        FOR ALL TO ssds_app
        USING (true)
        WITH CHECK (true);
END $$;
