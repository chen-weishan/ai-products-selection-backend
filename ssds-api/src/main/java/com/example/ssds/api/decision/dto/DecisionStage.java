package com.example.ssds.api.decision.dto;

/**
 * 決策在回饋閉環中走到哪一步（S-12 進度條：建立決策 → 開團 → 結案 → 回填）。
 *
 * <p>「開團」即 §7.4 的品項標記上架（ADOPTED → LISTED），由品項目前狀態判定。
 *
 * <p>資料庫沒有這個欄位，由 decision、campaign_end_date、campaign_result 是否存在推得。
 * 不落地是刻意的：三個來源已經足以決定狀態，多存一份只會出現不一致。
 * 此列舉為設計決定，規格書未定義。
 */
public enum DecisionStage {
    /** WATCH／REJECT：不開團，無結案與回填（AC-11-8）。 */
    NO_CAMPAIGN,
    /** ADOPT 但品項尚未標記上架（§7.4 ADOPTED → LISTED 之前）。 */
    AWAITING_LAUNCH,
    /** ADOPT、品項已上架（LISTED），尚未結案。 */
    IN_CAMPAIGN,
    /** 已結案、尚未回填。 */
    PENDING_RESULT,
    /** 已回填，閉環完成。 */
    COMPLETED
}
