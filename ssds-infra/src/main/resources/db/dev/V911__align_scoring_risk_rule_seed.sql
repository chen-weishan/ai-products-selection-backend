-- V900 已被其他成員與共享環境使用，不可回頭修改 checksum。
-- 本檔在 V900/V910 之後，以增量方式把 dev 假資料對齊 §5.2.2 正式契約。

UPDATE risk_rule
SET threshold_json = threshold_json ||
        '{"negativeRateThreshold":0.15,"minSampleSize":20}'::jsonb,
    max_penalty = 20.0,
    updated_at = now()
WHERE rule_code = 'REVIEW_RISK';

UPDATE risk_rule
SET threshold_json = threshold_json ||
        '{"meltableSummerPoints":4,"coldChainPoints":4,"fragilePoints":3,"oversizedPoints":3}'::jsonb,
    max_penalty = 10.0,
    updated_at = now()
WHERE rule_code = 'LOGISTICS_RISK';

UPDATE risk_rule
SET threshold_json = threshold_json ||
        '{"shelfLifeDaysThreshold":60,"shortShelfLifePoints":4,"seasonalPoints":3,"moqThreshold":300,"highMoqPoints":3}'::jsonb,
    max_penalty = 10.0,
    updated_at = now()
WHERE rule_code = 'INVENTORY_RISK' AND category_id IS NULL;

UPDATE risk_rule
SET threshold_json = threshold_json ||
        '{"shortShelfLifePoints":4,"seasonalPoints":3,"highMoqPoints":3}'::jsonb,
    max_penalty = 10.0,
    updated_at = now()
WHERE rule_code = 'INVENTORY_RISK' AND category_id IS NOT NULL;
