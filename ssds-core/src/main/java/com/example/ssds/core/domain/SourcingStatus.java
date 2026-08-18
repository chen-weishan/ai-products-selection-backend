package com.example.ssds.core.domain;

/**
 * 尋源狀態（規格書 FR-16-2、§7.2 sourcing_candidate.status）。
 *
 * <p>URGENT 對應「需加速尋源」（時效落差 0～14 天）；
 * 落差小於 0 者自動落到 REJECTED 且不可加入尋源清單（AC-16-4）。
 */
public enum SourcingStatus {
    /** 待評估 */
    PENDING,
    /** 尋源中 */
    SOURCING,
    /** 需加速尋源 */
    URGENT,
    /** 已成案，轉為 A 軌 */
    PROMOTED,
    /** 已淘汰（灰底保留，供下次同關鍵字出現時參考） */
    REJECTED
}
