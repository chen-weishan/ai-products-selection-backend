-- 修正既有開發資料的 Agent 7 展示文字。
-- 不修改已套用的 V903／V905，讓既有 dev 資料庫可由 Flyway 正常前進。
UPDATE calibration_report
SET ai_interpretation =
        '樣本數僅 6 筆，任何一筆的變動都會顯著改變相關係數，因此以下觀察僅供方向參考，不足以支持權重調整。'
        || E'\n\n'
        || '值得注意的是價格帶適配度的相關係數（0.64）明顯高於現行 7% 權重所反映的重要性，僅次於社群熱度趨勢（0.71）。'
        || '價格帶適配度反映售價與同品類主要客群價格帶的契合程度，對常態補貨型品項尤其重要；'
        || '即使社群熱度與毛利表現良好，價格帶錯位仍可能壓低歷史轉換表現。'
        || '建議在樣本累積至 200 筆後優先驗證這項訊號。'
        || E'\n\n'
        || '另需留意，情境覆寫集中於「食品／零食」品類（6 筆決策中有 2 筆覆寫，皆為該品類）。'
        || '若覆寫呈現系統性，可能需要先調整節慶檔期型品項的情境判定邏輯，而非直接修改權重。'
WHERE id = 1
  AND sample_size = 6
  AND status = 'PENDING'
  AND (ai_interpretation LIKE '%PRICE_FIT%'
       OR ai_interpretation LIKE '%price_fit%'
       OR ai_interpretation LIKE '%§%'
       OR ai_interpretation LIKE '%SceneClassifierAgent%');
