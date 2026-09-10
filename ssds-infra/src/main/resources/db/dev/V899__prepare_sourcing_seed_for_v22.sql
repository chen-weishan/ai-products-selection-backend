-- ===================================================================
-- V899 開發假資料相容層（V22 前後銜接）
--
-- 正式 V22 會先於 V900～V907 執行，並移除 sourcing_candidate 的
-- heat_stage / stage_weeks / estimated_lifespan_days；既有 V903 已發布，
-- 不可直接修改，否則已套用 V903 的成員會遇到 Flyway checksum mismatch。
--
-- 因此只在 dev location 暫時補回三個舊 seed 欄位，讓 V903 可維持原
-- checksum 寫入假資料；V908 會依新模型重算資料後再次移除。
-- ===================================================================

ALTER TABLE sourcing_candidate
    ADD COLUMN IF NOT EXISTS heat_stage VARCHAR(16),
    ADD COLUMN IF NOT EXISTS stage_weeks SMALLINT,
    ADD COLUMN IF NOT EXISTS estimated_lifespan_days INT;

