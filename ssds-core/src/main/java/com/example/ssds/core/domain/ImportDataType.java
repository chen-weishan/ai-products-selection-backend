package com.example.ssds.core.domain;

/** 匯入資料類型（規格書 §7.2.11 import_batch.data_type、FR-09）。 */
public enum ImportDataType {
    /** 歷史銷售紀錄 */
    SALES,
    /** 商品評論 */
    REVIEW,
    /** 會員輪廓（客群區隔） */
    AUDIENCE,
    /** 品項主檔 */
    PRODUCT
}
