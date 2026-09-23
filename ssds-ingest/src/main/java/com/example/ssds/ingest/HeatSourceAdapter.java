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

    /** 健康檢查排程（{@code HeatSourceHealthCheckJob}）探測用的佔位查詢字串。 */
    String PROBE_TARGET = "probe";

    /**
     * 健康檢查用的極輕量探測（規格書 §FR-14-2：「對每個 enabled 的來源送出一次極輕量查詢」）。
     *
     * <p><b>⚠️ 已知限制：預設實作直接呼叫 {@link #fetch} 對單一佔位目標送出一次真實查詢，
     * 而非真正意義上的「輕量」探測（例如只驗證 API 金鑰有效性、不觸發實際採集）。</b>
     * 三個既有 adapter（Threads／Google Trends／Instagram）背後都是 Apify 第三方 actor，
     * 本次補實作沒有逐一深入每個 client 找出真正低成本的探測方式（例如 Apify 是否有
     * 獨立的「金鑰驗證」端點），所以先提供這個通用但保守的預設值，讓每 15 分鐘一次的
     * 健康檢查排程至少能運作；之後若確認會頻繁消耗 Apify 額度或耗時過長，
     * 應由各 adapter 覆寫本方法，改用更輕量的驗證方式。
     *
     * @return 探測成功回傳 true；任何例外（連線失敗、金鑰失效等）一律視為探測失敗回傳 false，
     *         不向外拋出——健康檢查排程需要能持續巡檢所有來源，單一來源探測失敗不該中斷整批
     */
    default boolean probe() {
        try {
            fetch(List.of(PROBE_TARGET), LocalDate.now());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}