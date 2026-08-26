package com.example.ssds.core.domain;

/**
 * 評分因子代號（規格書 §5.2）。
 *
 * <p>加分因子參與情境權重調整；扣分因子固定生效、不參與權重。
 * 分離的理由見 §5.1：客戶希望社群熱度高的商品即使評價普通仍能開團，
 * 但「負評集中在食安」這種狀況不能因為調低評論權重就被放行。
 *
 * <p>maxPenalty 只對扣分因子有意義，加分因子固定為 0。
 *
 * <p>值域為 v3.0 §7.2.5／§7.2.6 的六個加分因子加三個扣分因子。
 * v2.0 的 {@code HEAT_SLOPE} 與 {@code CONVERSION} 於 v3.0 更名為
 * {@code TREND} 與 {@code CVR}；{@code HEAT_VOLUME} 已由加權因子降級為
 * 門檻條件（§5.2.1-a），該資訊改由 {@code heat_composite_daily.volume_below_floor}
 * 承載，不再是因子。資料庫端的 CHECK 由 V17 一併收斂，舊值寫不進去。
 */
public enum FactorCode {

    /** 社群熱度斜率，7 日與 30 日雙窗口 */
    TREND(false, 0),
    /** 毛利率＝（售價－成本）／售價，僅 A 軌 */
    MARGIN(false, 0),
    /** 歷史轉換率，同品類歷史開團表現，僅 A 軌 */
    CVR(false, 0),
    /** 價格帶適配度，對照主力客群價格敏感區間 */
    PRICE_FIT(false, 0),
    /** 節慶時間窗，時間窗函數而非常數（FR-17-1） */
    FESTIVAL(false, 0),
    /** 季節氣候適配，取歷史同期氣候統計（FR-17-2） */
    CLIMATE(false, 0),

    /** 評論風險：負評率超過品類門檻「且」集中於品質／食安／物流破損 */
    REVIEW_RISK(true, 20),
    /** 物流風險：冷鏈需求、易融化、易碎、材積異常 */
    LOGISTICS_RISK(true, 10),
    /** 庫存風險：效期短、季節性強、最小訂購量偏高 */
    INVENTORY_RISK(true, 10);

    /** 扣分小計的上限（§5.5）。 */
    public static final int PENALTY_CAP = 40;

    /** 扣分達此值時分級最高只給 B，且強制進入風險示警清單（§5.6 硬規則）。 */
    public static final int PENALTY_GRADE_SUPPRESS_THRESHOLD = 20;

    private final boolean penalty;
    private final int maxPenalty;

    FactorCode(boolean penalty, int maxPenalty) {
        this.penalty = penalty;
        this.maxPenalty = maxPenalty;
    }

    /** 是否為扣分因子。對應 score_factor.is_penalty。 */
    public boolean isPenalty() {
        return penalty;
    }

    /** 該扣分因子的最大扣分值（加分因子為 0）。 */
    public int maxPenalty() {
        return maxPenalty;
    }
}
