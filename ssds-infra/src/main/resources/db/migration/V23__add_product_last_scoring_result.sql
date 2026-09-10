ALTER TABLE product
    ADD COLUMN last_scoring_status VARCHAR(32),
    ADD COLUMN last_scoring_attempted_at TIMESTAMPTZ;

ALTER TABLE product
    ADD CONSTRAINT ck_product_last_scoring_status
        CHECK (last_scoring_status IS NULL
            OR last_scoring_status IN ('SCORED', 'INSUFFICIENT_DATA'));

COMMENT ON COLUMN product.last_scoring_status IS
    '最近一次已完成評分嘗試的技術結果；NULL 表示尚未嘗試評分';
COMMENT ON COLUMN product.last_scoring_attempted_at IS
    '最近一次已完成評分嘗試時間（UTC）';
