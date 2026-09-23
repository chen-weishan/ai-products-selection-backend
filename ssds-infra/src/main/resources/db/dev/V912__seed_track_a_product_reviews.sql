-- Dev-only seed：讓目前資料庫中所有未刪除的 A 軌品項至少有 20 則評論。
--
-- ReviewRiskAgent 與 ProductInsightAgent 都會讀 product_review；少於 20 則時，
-- REVIEW_RISK 依規格不扣分，而完全沒有評論時 Agent 2／3 不會呼叫 LLM。
-- 本檔只補原始評論，不預寫 review_analysis 或 ai_insight，確保 FULL_ANALYSIS
-- 仍會實際走 Agent 2、3、4 的串接流程。
--
-- 既有評論一律保留；已達 20 則的品項不異動。內容以固定模板產生，搭配
-- (product_id, content_hash) 唯一鍵與 ON CONFLICT，使腳本具備冪等性。

WITH review_counts AS (
    SELECT p.id AS product_id,
           p.name AS product_name,
           count(r.id) AS review_count
    FROM product p
    LEFT JOIN product_review r ON r.product_id = p.id
    WHERE p.track_type = 'A'
      AND p.deleted_at IS NULL
    GROUP BY p.id, p.name
    HAVING count(r.id) < 20
),
seed_reviews AS (
    SELECT rc.product_id,
           'V912_DEV_SEED'::varchar(50) AS source,
           rc.product_name || '：' || CASE generated.review_no
               WHEN 1  THEN '包裝完整，收到時商品外觀保持得很好。'
               WHEN 2  THEN '實際品質符合商品說明，整體使用感受不錯。'
               WHEN 3  THEN '配送速度合理，商品也有妥善固定。'
               WHEN 4  THEN '家人試過後都覺得可以，願意再次購買。'
               WHEN 5  THEN '份量與價格搭配合理，整體有達到期待。'
               WHEN 6  THEN '外盒設計清楚，保存與使用方式容易理解。'
               WHEN 7  THEN '品質穩定，這次收到的狀況和先前一樣好。'
               WHEN 8  THEN '商品特色明確，適合日常使用或分享。'
               WHEN 9  THEN '客服回覆很快，詢問的問題都有獲得說明。'
               WHEN 10 THEN '整體表現中規中矩，沒有特別需要改善的地方。'
               WHEN 11 THEN '商品本身正常，但配送時間比預期稍久。'
               WHEN 12 THEN '內容物完整，不過外包裝有輕微壓痕。'
               WHEN 13 THEN '第一次購買，實際體驗比預期更好。'
               WHEN 14 THEN '規格標示清楚，收到後很容易確認內容。'
               WHEN 15 THEN '使用後感受良好，品質與價格相符。'
               WHEN 16 THEN '包裝有妥善保護，運送過程沒有造成損壞。'
               WHEN 17 THEN '商品品質不穩定，這次收到的狀況需要改善。'
               WHEN 18 THEN '外箱明顯變形，內容物也受到擠壓。'
               WHEN 19 THEN '實際質感普通，和商品描述有一些落差。'
               WHEN 20 THEN '整體仍可使用，但希望後續能加強品質檢查。'
           END AS content,
           CASE generated.review_no
               WHEN 10 THEN 3.0
               WHEN 11 THEN 3.0
               WHEN 12 THEN 3.0
               WHEN 17 THEN 2.0
               WHEN 18 THEN 1.5
               WHEN 19 THEN 2.5
               WHEN 20 THEN 3.0
               ELSE 4.5
           END::decimal(2, 1) AS rating,
           CURRENT_DATE - (21 - generated.review_no)::integer AS reviewed_at
    FROM review_counts rc
    CROSS JOIN LATERAL generate_series(rc.review_count + 1, 20) AS generated(review_no)
)
INSERT INTO product_review (product_id, source, content, rating, reviewed_at, content_hash)
SELECT product_id,
       source,
       content,
       rating,
       reviewed_at,
       encode(sha256(convert_to(content, 'UTF8')), 'hex')
FROM seed_reviews
ON CONFLICT (product_id, content_hash) DO NOTHING;
