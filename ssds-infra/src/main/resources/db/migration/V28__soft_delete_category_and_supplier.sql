-- 類別與供應商主檔採軟刪除，保留既有品項與歷史資料的外鍵關聯。
ALTER TABLE category
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deleted_by BIGINT REFERENCES app_user (id);

ALTER TABLE supplier
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deleted_by BIGINT REFERENCES app_user (id);

COMMENT ON COLUMN category.deleted_at IS
    '主檔軟刪除時間；已刪除類別不得再用於新建、修改、匯入或下拉選單';
COMMENT ON COLUMN supplier.deleted_at IS
    '主檔軟刪除時間；已刪除供應商不得再用於新建、修改、匯入或下拉選單';

CREATE INDEX IF NOT EXISTS idx_category_active
    ON category (parent_id, sort_order, name) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_supplier_active_name
    ON supplier (name) WHERE deleted_at IS NULL;
