-- dev 假資料的登入密碼修正。
--
-- V900 第 41 行的註解寫「password_hash 全部是同一組 BCrypt(cost=10)，明碼為 Ssds@2026」，
-- 這句話不成立。七個帳號的值都是
--   $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
-- 這是網路教學文件裡到處複製的樣板字串，對不上任何明碼。
--
-- 已驗證（2026-09-11，Python bcrypt 5.0.0，先以 checkpw 自我測試確認函式庫正常）：
--   Ssds@2026 / password / Password / secret / 123456 / admin 逐一 checkpw 全為 False。
-- 本檔的新 hash 以 cost=10、$2a$ 前綴產生，回驗 Ssds@2026 為 True、錯誤密碼為 False。
--
-- 為什麼是新開一支而不是直接改 V900：
-- V900 已經套用在共用資料庫上，改動任何一個字（連註解都算）都會讓 Flyway
-- checksum 對不上，所有人都得先 repair 才能再 migrate。
--
-- 這支的觸發時機：FR-01 登入（PR #11）上線後，登入流程才第一次真的去驗這個欄位。
-- 在那之前壞 hash 不會有任何症狀，所以放到現在才被發現。
--
-- 注意：PR #11 的 SecurityConfig 註冊的是 BCryptPasswordEncoder（不是 Spring 預設的
-- DelegatingPasswordEncoder），所以這裡存裸 hash、不加 {bcrypt} 前綴是正確的。
-- 若日後換成 DelegatingPasswordEncoder，缺前綴會讓登入丟
-- IllegalArgumentException（症狀是 500 而不是「密碼錯誤」），屆時要一併改。

UPDATE app_user
SET password_hash = '$2a$10$Drdp9oSqfZNMoxyK8uXPnutsREX/I5869KOCjJvCtRCo9hZnxu1Ra'
WHERE email IN (
    'buyer@ssds.dev',
    'lead@ssds.dev',
    'dataadmin@ssds.dev',
    'sysadmin@ssds.dev',
    'viewer@ssds.dev',
    'buyer2@ssds.dev',
    'disabled@ssds.dev'
);
