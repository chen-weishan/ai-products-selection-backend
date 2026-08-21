package com.example.ssds.core.domain;

/**
 * 情境原型（規格書 §5.4、FR-08）。
 *
 * <p>SceneClassifierAgent 每次評分時判定情境，但<b>只能從本列舉中挑選</b>，
 * 不得自訂權重數值 —— 若允許 AI 自由填權重，它會傾向給該品項表現好的因子高權重，
 * 導致所有品項分數偏高、鑑別力歸零（FR-08 設計理由）。
 *
 * <p>信心值低於 0.5 或 Schema 驗證失敗時，一律退回 REPLENISHMENT。
 */
public enum SceneType {
    /** 話題爆款型：社群竄升、生命週期短 */
    VIRAL,
    /** 節慶檔期型：明確對應檔期 */
    FESTIVAL,
    /** 常態補貨型：穩定需求、重複開團（同時是判定失敗時的預設值） */
    REPLENISHMENT,
    /** 季節導向型：強季節性商品 */
    SEASONAL;

    /** 情境判定信心不足或 Schema 驗證失敗時的退回值（§5.4）。 */
    public static final SceneType FALLBACK = REPLENISHMENT;
}
