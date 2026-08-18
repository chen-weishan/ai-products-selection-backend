package com.example.ssds.core.domain;

/** 匯入資料類型（規格書 FR-09、§7.2 import_batch.data_type）。 */
public enum ImportDataType {
    /** 歷史銷售紀錄 */
    SALES_RECORD,
    /** 商品評論 */
    PRODUCT_REVIEW,
    /** 會員輪廓 */
    MEMBER_PROFILE,
    /** 品項主檔 */
    PRODUCT_MASTER
}
