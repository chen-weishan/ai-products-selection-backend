-- SceneClassifierAgent 的可稽核輸出。既有情境 enum 保持不變，API 層另映射規格名稱。
ALTER TABLE scene_classification_log
    ADD COLUMN alternative_scene_type VARCHAR(24)
        CHECK (alternative_scene_type IN ('VIRAL_TOPIC', 'FESTIVAL', 'STAPLE_RESTOCK', 'SEASONAL')),
    ADD COLUMN signals JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN fallback_applied BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN fallback_reason VARCHAR(32),
    ADD COLUMN model VARCHAR(80),
    ADD COLUMN prompt_version VARCHAR(20),
    ADD COLUMN heat_bucket VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE scene_classification_log
    ADD CONSTRAINT ck_scene_fallback_reason CHECK (
        (fallback_applied AND fallback_reason IS NOT NULL)
        OR (NOT fallback_applied AND fallback_reason IS NULL)
    );

COMMENT ON COLUMN scene_classification_log.signals IS
    'SceneClassifierAgent 的依據訊號，供 S-06 情境判定橫幅顯示';
COMMENT ON COLUMN scene_classification_log.heat_bucket IS
    '七日快取 key 的熱度區間；同品項跨區間時必須重新判定';
