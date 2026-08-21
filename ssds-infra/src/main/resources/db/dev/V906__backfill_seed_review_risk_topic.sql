-- dev 假資料補齊：回填 V903 種下的 review_analysis 的 risk_topic。
--
-- 【為什麼需要這支】
-- V14 的回填只掃得到「執行當下已存在」的列。共用資料庫上 V903 早就跑完，
-- 所以那批假資料有被回填到；但從空庫重建的環境（Testcontainers、組員自己的
-- Supabase）順序是 V14 先跑（表是空的，回填 0 列）、V903 之後才插入，
-- 於是所有假負評的 risk_topic 都是 NULL，Agent 2 在本地沒有測試資料可用。
--
-- 【為什麼不在正式 schema 裡放 trigger】
-- 用 BEFORE INSERT trigger 同步也能補上，但那是永久存在的正式物件，
-- 只為 dev 假資料服務，而且會在應用程式寫入時回頭覆寫 Agent 2 算出的值。
-- 假資料的問題就用 dev 版面的 migration 解決。
--
-- 【WHERE 的三個條件為什麼是安全的】
-- prompt_version IS NULL 是關鍵：V903 的 INSERT 不帶這個欄位，而 V14 之後
-- 任何一筆真正由 Agent 2 寫入的分析都會帶版本。所以這個條件只命中假資料，
-- 命不到組員寫進共用資料庫的真實結果。共用資料庫上 V903 那批已被 V14
-- 回填成 'pre-v14'，本檔在那裡會匹配 0 列——冪等，重跑無害。
--
-- CASE 的分支與優先序與 V14 一致，刻意重複而不抽成函式：
-- 假資料的回填規則不該和正式 schema 共用可變的東西。
UPDATE review_analysis
SET risk_topic = CASE
    WHEN aspects IS NULL                            THEN 'OTHER'
    WHEN aspects ~ '(食安|FOOD_SAFETY)'             THEN 'FOOD_SAFETY'
    WHEN aspects ~ '(品質|QUALITY)'                 THEN 'QUALITY'
    WHEN aspects ~ '(物流破損|SHIPPING_DAMAGE)'     THEN 'SHIPPING_DAMAGE'
    WHEN aspects ~ '(價格|PRICE)'                   THEN 'PRICE'
    ELSE 'OTHER'
END
WHERE sentiment = 'NEGATIVE'
  AND risk_topic IS NULL
  AND prompt_version IS NULL;
