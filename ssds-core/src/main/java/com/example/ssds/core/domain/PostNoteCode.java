package com.example.ssds.core.domain;

/**
 * 結案事後註記（規格書 FR-11-2）。
 *
 * <p>刻意做成單選代碼而非自由文字：FR-11-2 明訂回填欄位壓在 5 個以內，
 * 欄位一多就沒人填，沒人填 FR-15 的權重校準就沒有標籤資料可用。
 */
public enum PostNoteCode {
    /** 爆得比預期快 */
    FASTER_THAN_EXPECTED,
    /** 熱度已過 */
    HEAT_FADED,
    /** 品質問題 */
    QUALITY_ISSUE,
    /** 物流出狀況 */
    LOGISTICS_ISSUE
}
