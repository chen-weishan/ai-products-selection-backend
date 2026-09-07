package com.example.ssds.ingest;

import com.example.ssds.core.domain.HeatSourceCode;
import java.time.LocalDate;
import java.util.List;

/**
 * 熱度來源 adapter 的統一介面（規格書 §3.3 / FR-14）。
 * 每個外部來源（Instagram、其他平台⋯）各自實作一份。
 *
 * <p>單一 target（如單一 hashtag）查詢失敗時，實作應只記警告並跳過該筆，
 * 不中斷整批；只有「整批呼叫本身失敗」（如 API 金鑰失效、完全連不上）
 * 才應該向外拋出例外。
 */
public interface HeatSourceAdapter {

    /** 此 adapter 對應的熱度來源代碼。 */
    HeatSourceCode sourceCode();

    /**
     * 查詢指定 targets 在指定日期的熱度原始值。
     *
     * @param targets 要查詢的目標清單（如 hashtag 清單）
     * @param date    查詢日期
     * @return 成功取得的讀值清單；查無資料的 target 直接跳過，不會出現在結果中
     */
    List<HeatDataPoint> fetch(List<String> targets, LocalDate date);
}