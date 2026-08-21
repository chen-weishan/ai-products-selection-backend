-- 承 V14：review_analysis.prompt_version 由 NOT NULL 放寬為可為空。
--
-- 【為什麼要放寬】
-- V14 把這個欄位建成 NOT NULL DEFAULT 'pre-v14'，回填後又 DROP DEFAULT。
-- 在共用資料庫上沒有問題——V903 早在 V14 之前就套用完畢了。
-- 但 dev 版面的 V903 版本號是 900 系列，永遠排在 V14 之後，且它的
-- INSERT INTO review_analysis 不帶 prompt_version。於是任何從空庫重建的
-- 環境（Testcontainers、組員自己的 Supabase）都會在 V903 當場失敗：
--
--   ERROR: null value in column "prompt_version" of relation
--          "review_analysis" violates not-null constraint
--
-- V14 已經套用並共享，不能修改，只能由本檔放寬。
--
-- 【為什麼不是改回 DEFAULT 'pre-v14'】
-- 那會讓日後忘記帶版本的寫入被靜默標成 'pre-v14'。這個欄位存在的唯一
-- 理由就是回溯「哪一版 prompt 產的」，標錯比留空更難查。留 NULL 至少
-- 一眼看得出是缺值。
--
-- 【對寫入端的要求不變】
-- Agent 2 的寫入路徑仍應顯式帶入 prompt_version。放寬的是資料庫的底線，
-- 不是應用程式的責任。
ALTER TABLE review_analysis
    ALTER COLUMN prompt_version DROP NOT NULL;

COMMENT ON COLUMN review_analysis.prompt_version IS
    '產生分析結果的 Prompt 模板版本；V14 前的既有資料標記為 pre-v14。'
    'V15 起可為空且無 DEFAULT：dev seed V903 排在 V14 之後且不帶此欄，'
    '而 NOT NULL DEFAULT 會讓忘記帶版本的寫入被靜默標成 pre-v14，'
    '留 NULL 才看得出是缺值。寫入端仍應顯式帶入';
