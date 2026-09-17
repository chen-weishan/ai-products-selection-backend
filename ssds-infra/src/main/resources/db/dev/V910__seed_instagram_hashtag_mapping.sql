-- instagram_hashtag_mapping（V29 建的表）的假資料，原本是
-- InstagramHashtagMapping.ENTRIES 裡寫死的兩筆示範對照。
--
-- 放在 db/dev、版號排在 V900 之後：V29 join 的「零食」「生鮮冷凍」這兩個
-- 品類名稱只在 V900 的假資料裡才存在，正式 schema（db/migration）不保證有
-- 同名品類。若把 INSERT 放在 V29（版號 29 < 900），dev 環境套用時 V29 會先
-- 跑，那個當下 category 表還是空的，JOIN 到 0 筆、這張表就會悄悄留空，
-- 而不是報錯——所以刻意分成兩支、種資料的那支排在品類已經存在之後。
INSERT INTO instagram_hashtag_mapping (hashtag, category_id)
SELECT v.hashtag, c.id
FROM (VALUES ('matcha', '零食'), ('低醣', '生鮮冷凍')) AS v(hashtag, category_name)
JOIN category c ON c.name = v.category_name
WHERE NOT EXISTS (
    SELECT 1 FROM instagram_hashtag_mapping m WHERE m.hashtag = v.hashtag
);
