package com.example.ssds.core.domain;

/**
 * 熱度來源代碼（規格書 §7.2 heat_source.source_code）。
 *
 * <p>Facebook、TikTok、小紅書不在此列 —— 三者均無合法的程式化資料管道
 * （附錄 C 法律評估），改由 MANUAL 人工標記涵蓋。
 */
public enum HeatSourceCode {
    THREADS,
    GOOGLE_TRENDS,
    INSTAGRAM,
    MANUAL
}
