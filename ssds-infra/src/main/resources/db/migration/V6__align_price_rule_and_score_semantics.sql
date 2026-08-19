-- ===================================================================
-- V6 對齊 FR-03-2 的定價規則，並釐清 base_score 與 bonus_subtotal 的語意
-- ===================================================================
--
-- 【為什麼是新的一支 migration 而不是改 V1】
-- V1–V5 與 V900–V903 都已經在團隊的 Supabase 上執行過了。Flyway 會對每一支
-- 已套用的 migration 存 checksum，回頭改檔案內容會讓下次啟動直接
-- validation 失敗。已套用的 migration 視為不可變，修正一律往後加。
-- ===================================================================


-- ===================================================================
-- 1. 定價規則：成本等於售價時也要擋下來
-- ===================================================================
-- FR-03-2 的例外條件寫的是「成本 ≥ 售價 → 阻擋儲存並提示」，
-- 也就是唯一可儲存的情況是「成本 < 售價」。
-- 原本的約束寫成 suggested_price >= cost，會放行「成本 = 售價」，
-- 那正是 FR-03-2 要擋的其中一種情況（毛利率為 0，評分沒有意義）。

ALTER TABLE product DROP CONSTRAINT ck_product_price;

ALTER TABLE product ADD CONSTRAINT ck_product_price CHECK (
    cost IS NULL OR suggested_price IS NULL OR suggested_price > cost
);

COMMENT ON CONSTRAINT ck_product_price ON product IS
    'FR-03-2 例外條件：成本 ≥ 售價 → 阻擋儲存。故此處要求嚴格大於，成本等於售價亦不可儲存';

-- FR-03-2 的另一條例外是「同類別同名品項 → 警告但允許儲存」。
-- 「允許儲存」表示這件事<b>不可以</b>做成唯一鍵 —— 資料庫端不設限制，
-- 由應用層在存檔前查詢並回傳警告，讓使用者自行決定。
-- 既有的 idx_product_category_status 與 idx_product_name 都是非唯一索引，
-- 重名偵測查詢走得到索引，行為也符合規格。
COMMENT ON COLUMN product.name IS
    'FR-03-2：同類別同名允許儲存，僅由應用層提出警告，故本欄與 category_id 刻意不建唯一鍵';


-- ===================================================================
-- 2. base_score 與 bonus_subtotal 是兩個不同的值
-- ===================================================================
-- §5.5 的計算範例是：
--     加權和 = Σ(w_i × normalized_i) = 76.3
--     加分小計 = 76.3 經「同品類百分位換算」後 = 91
--     選品分數 = max(0, 91 − 4) = 87
--
-- 換算函式即 §5.3.1：
--     normalized(x) = percentile_rank(x, same_category_values) × 100
--     同品類樣本數 < 10 時退回全品類百分位，並降低信心度（§5.9 −20）
--
-- 也就是百分位正規化在本系統套用<b>兩次</b>：
--   第一次在單一因子層級（score_factor.normalized_value）
--   第二次在加權後的總分層級（base_score → bonus_subtotal）
--
-- 因此兩個欄位的語意如下，不再是「同一個值的新舊名稱」：
--   base_score      加權和，等於 score_factor 各列 normalized_value × weight 的總和
--   bonus_subtotal  加權和經同品類百分位換算後的加分小計，參與 §5.5 的減法

COMMENT ON COLUMN product_score.base_score IS
    '加權和 Σ(w_i × normalized_i)，尚未做同品類百分位換算。等於 score_factor 各加分列貢獻值的總和';

COMMENT ON COLUMN product_score.bonus_subtotal IS
    '加分小計：base_score 經 §5.3.1 同品類百分位換算後的值（0–100），參與 §5.5 的 final_score 計算。'
    '同品類樣本數 < 10 時改用全品類百分位，並依 §5.9 扣 20 點信心度';

-- risk_penalty 與 penalty_subtotal 則確實是同一個值的新舊欄位名
-- （§7.2 原表列 risk_penalty，v2.0 新增欄位又列了 penalty_subtotal），
-- 兩者一律同步寫入。
COMMENT ON COLUMN product_score.risk_penalty IS
    'v1.0 欄位名，與 penalty_subtotal 為同一個值，一律同步寫入。扣分不做百分位換算';
