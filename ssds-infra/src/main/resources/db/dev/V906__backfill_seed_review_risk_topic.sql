-- dev 假資料檢查：確認 V903 種下的負評分析都有 risk_topic。
--
-- 【這支原本在做什麼】
-- V903 以前不寫 risk_topic，本檔負責從 review_analysis.aspects 這個逗號分隔
-- 字串反推並回填。aspects 已於 V17 移除（§7.2.4 明訂改為結構化的 risk_topic），
-- 回填的來源欄位就此消失，這支的原本做法失去依據。
--
-- 【現在的做法】
-- 分類改由 V903 在 INSERT 當下直接依評論內容判定 —— 那才是唯一還在的來源，
-- 而且少一次「先寫錯再改對」的來回。本檔改為驗證，理由有二：
--   1. 版本號不能回收。V906 已套用於共用資料庫，刪檔會讓 Flyway validate 失敗
--   2. 「每一筆 NEGATIVE 都要有 risk_topic」本來就值得有人守著：
--      §5.2.2 的負評率與 §FR-05 的風險主題分佈都靠這欄，
--      漏一筆不會報錯，只會讓畫面上的分佈少一塊
--
-- 只檢查假資料的範圍（V903 的評論一律掛在品項 101–135 上），
-- 不掃全表：組員寫進共用資料庫的真實分析不該擋下整組人的啟動。

DO $$
DECLARE
    n_missing INT;
BEGIN
    SELECT COUNT(*) INTO n_missing
    FROM review_analysis ra
             JOIN product_review r ON r.id = ra.review_id
    WHERE ra.sentiment = 'NEGATIVE'
      AND ra.risk_topic IS NULL
      AND r.product_id BETWEEN 101 AND 135;

    IF n_missing > 0 THEN
        RAISE EXCEPTION
            'V906 中止：有 % 筆假資料負評分析沒有 risk_topic。'
            'V903 的 review_analysis 分類邏輯與評論內容對不上了', n_missing;
    END IF;

    RAISE NOTICE 'V906 驗證通過：假資料的負評分析都有 risk_topic';
END $$;
