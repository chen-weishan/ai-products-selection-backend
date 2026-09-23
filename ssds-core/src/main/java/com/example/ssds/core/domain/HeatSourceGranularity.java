package com.example.ssds.core.domain;

/**
 * 熱度來源的資料粒度（規格書 FR-14-2、§7.2 heat_source.granularity）。
 *
 * <p>決定 {@code heat_reading} 該用哪一個目標欄位：關鍵字級來源
 * （THREADS、GOOGLE_TRENDS、MANUAL）寫 {@code keyword_id}，
 * 品類級來源（INSTAGRAM）寫 {@code category_id}（見 §7.2.3 V17 裁決）。
 *
 * <p>DB 端 {@code ck_heat_source_granularity} 只允許這兩個值
 * （見 V17__align_schema_to_spec_v3.sql）。
 */
public enum HeatSourceGranularity {
    KEYWORD,
    CATEGORY
}
