package com.example.ssds.core.domain;

/** 報表類型（規格書 §7.2.11 report_job.report_type、FR-12 五種報表）。 */
public enum ReportType {
    /** 每週選品建議 */
    WEEKLY_PICK,
    /** 品項分數明細 */
    SCORE_DETAIL,
    /** 推薦準確率 */
    ACCURACY,
    /** 尋源待辦清單（B 軌） */
    SOURCING_QUEUE,
    /** 權重校準報告 */
    CALIBRATION
}
