-- 期中 demo 用：五個角色的主帳號顯示名稱改為角色名稱，讓觀眾一眼看出目前登入的是哪個角色。
-- 角色名稱取自規格書 §2 角色表。
--
-- 只改一人一角色的五個主帳號；buyer2（多人標記測試）與 disabled（停用測試）維持原名，
-- 它們不是 demo 登入用帳號。
--
-- 以 email 為條件而非 id，且帶舊名稱比對：在全新資料庫上 V900 會先建出這些帳號，
-- 此檔照常生效；若有人已手動改過名稱則不覆蓋。

UPDATE app_user SET display_name = '採購專員'   WHERE email = 'buyer@ssds.dev'     AND display_name = '林采薇';
UPDATE app_user SET display_name = '採購主管'   WHERE email = 'lead@ssds.dev'      AND display_name = '陳建豪';
UPDATE app_user SET display_name = '資料管理員' WHERE email = 'dataadmin@ssds.dev' AND display_name = '黃詩涵';
UPDATE app_user SET display_name = '系統管理員' WHERE email = 'sysadmin@ssds.dev'  AND display_name = '王紹安';
UPDATE app_user SET display_name = '唯讀觀察者' WHERE email = 'viewer@ssds.dev'    AND display_name = '吳靜宜';
