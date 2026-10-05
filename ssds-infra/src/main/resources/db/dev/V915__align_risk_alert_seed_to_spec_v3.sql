-- 開發環境專用：把示警假資料對齊《開發規格書 v3.0》§FR-10-1。
--
-- 為什麼要再寫一次 V34 的 UPDATE：
-- Flyway 依版號排序，V34 在乾淨資料庫上會先於 V900 執行，當時 risk_rule 還是空的，
-- 那四句 UPDATE 什麼都沒改到；V900 隨後才插入舊值。所以 dev 需要一支排在
-- V900 之後的檔案補上（同 V911 對 V30 的作法，V900 已被共用環境使用，不可改 checksum）。

-- ---- 1. risk_rule：與 V34 相同 ----
UPDATE risk_rule
SET threshold_json = '{"penaltySubtotalThreshold": 20}'::jsonb, updated_at = now()
WHERE rule_code = 'PENALTY_CAP'
  AND threshold_json = '{"penaltySubtotalThreshold": 40}'::jsonb;

UPDATE risk_rule
SET threshold_json = '{"confidenceThreshold": 50}'::jsonb, updated_at = now()
WHERE rule_code = 'LOW_CONFIDENCE'
  AND threshold_json = '{"confidenceThreshold": 60}'::jsonb;

UPDATE risk_rule
SET threshold_json = '{"slopePercentile": 0.95}'::jsonb, updated_at = now()
WHERE rule_code = 'HEAT_SURGE' AND threshold_json ? 'slope7dThreshold';

UPDATE risk_rule
SET threshold_json = '{"climateFitPercentileThreshold": 20}'::jsonb, updated_at = now()
WHERE rule_code = 'SEASON_MISMATCH' AND threshold_json ? 'tempDeviationThreshold';

-- ---- 2. risk_alert 假資料：嚴重度與觸發描述改成規格書的預設值 ----
-- PENALTY_CAP 預設 HIGH（原 116 號品項為 MEDIUM）
UPDATE risk_alert SET severity = 'HIGH'
WHERE product_id = 116 AND risk_type = 'PENALTY_CAP';

-- LOGISTICS_RISK 預設 MEDIUM（原 104 號品項為 HIGH）
UPDATE risk_alert SET severity = 'MEDIUM'
WHERE product_id = 104 AND risk_type = 'LOGISTICS_RISK';

-- HEAT_CRASH 預設 HIGH，且觸發條件是 slope_7d <= -40%。
-- 原描述「7 日 −21%」依規格根本不會觸發，改成會觸發的數值。
UPDATE risk_alert
SET severity = 'HIGH',
    trigger_value = '7 日熱度斜率 −46%（門檻 −40%），30 日斜率 −38%'
WHERE product_id = 114 AND risk_type = 'HEAT_CRASH';

-- SEASON_MISMATCH 預設 MEDIUM，觸發條件是氣候適配百分位 < 20，
-- 原描述（「備貨期外的月份」）不是規格的判定式。
UPDATE risk_alert
SET severity = 'MEDIUM',
    trigger_value = '季節氣候適配百分位 12（門檻 20）'
WHERE product_id = 102 AND risk_type = 'SEASON_MISMATCH';

-- LOW_CONFIDENCE 門檻是 50，不是 60
UPDATE risk_alert
SET trigger_value = '評分信心 44，低於門檻 50；情境判定亦退回常態補貨型'
WHERE product_id = 114 AND risk_type = 'LOW_CONFIDENCE';
