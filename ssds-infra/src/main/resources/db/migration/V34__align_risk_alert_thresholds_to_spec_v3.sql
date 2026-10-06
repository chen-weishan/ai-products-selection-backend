-- 示警門檻對齊《開發規格書 v3.0》§FR-10-1。
--
-- 只 UPDATE、不 INSERT（同 V30 的原則：乾淨環境沒有規則列時不憑空插入資料）。
-- 數值型的修正只在「仍等於舊 seed 值」時才改，避免蓋掉有人刻意調過的值；
-- 結構改變（舊欄位在規格裡已無意義）則整份 threshold_json 換掉。
--
-- 對照（規格書 → 舊 seed）：
--   PENALTY_CAP     扣分小計 >= 20        → penaltySubtotalThreshold 40
--                   （40 是 §5.2.2 的「扣分合計上限」，不是示警門檻，兩者被混用）
--   LOW_CONFIDENCE  信心度 < 50           → confidenceThreshold 60
--   HEAT_SURGE      同品類當日 slope_7d 的 P95 以上，且熱度量級達下限
--                                         → 固定斜率 slope7dThreshold 0.60
--   SEASON_MISMATCH 季節氣候適配百分位 < 20 → tempDeviationThreshold 8.0
--   HEAT_CRASH（slope_7d <= -0.40）、FESTIVAL_WINDOW_CLOSING（7 日）本來就一致。

UPDATE risk_rule
SET threshold_json = '{"penaltySubtotalThreshold": 20}'::jsonb,
    updated_at = now()
WHERE rule_code = 'PENALTY_CAP'
  AND threshold_json = '{"penaltySubtotalThreshold": 40}'::jsonb;

UPDATE risk_rule
SET threshold_json = '{"confidenceThreshold": 50}'::jsonb,
    updated_at = now()
WHERE rule_code = 'LOW_CONFIDENCE'
  AND threshold_json = '{"confidenceThreshold": 60}'::jsonb;

UPDATE risk_rule
SET threshold_json = '{"slopePercentile": 0.95}'::jsonb,
    updated_at = now()
WHERE rule_code = 'HEAT_SURGE'
  AND threshold_json ? 'slope7dThreshold';

UPDATE risk_rule
SET threshold_json = '{"climateFitPercentileThreshold": 20}'::jsonb,
    updated_at = now()
WHERE rule_code = 'SEASON_MISMATCH'
  AND threshold_json ? 'tempDeviationThreshold';
