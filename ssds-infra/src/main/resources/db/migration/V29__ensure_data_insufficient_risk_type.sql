-- V27 on origin/dev already adds DATA_INSUFFICIENT. This later migration is
-- intentionally idempotent so this branch can record the alert now and can
-- still be merged with dev without a migration version collision.
ALTER TABLE risk_alert
    DROP CONSTRAINT IF EXISTS ck_risk_alert_type;

ALTER TABLE risk_alert
    ADD CONSTRAINT ck_risk_alert_type
        CHECK (risk_type IN (
            'REVIEW_RISK', 'LOGISTICS_RISK', 'INVENTORY_RISK', 'PENALTY_CAP',
            'HEAT_CRASH', 'HEAT_SURGE', 'SEASON_MISMATCH',
            'FESTIVAL_WINDOW_CLOSING', 'LOW_CONFIDENCE', 'DATA_INSUFFICIENT'));

COMMENT ON COLUMN risk_alert.risk_type IS
    '示警類型。§FR-10-1 九項，外加 DATA_INSUFFICIENT（§5.7 資料不足，無法評分）';
