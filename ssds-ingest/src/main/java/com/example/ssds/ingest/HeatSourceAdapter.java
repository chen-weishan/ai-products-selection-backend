package com.example.ssds.ingest;

import com.example.ssds.core.domain.HeatSourceCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

    /** 探測用的佔位查詢字串（僅供沒有覆寫 {@link #probe()} 的 adapter 預設實作使用）。 */
    String PROBE_TARGET = "probe";

    /**
     * 「測試連線」用的探測。
     *
     * <p>預設實作直接呼叫 {@link #fetch} 對單一佔位目標送出一次真實查詢——會消耗爬取額度，
     * 而且 adapter 的 fetch 會吞掉單一 target 的例外，實際上很難回 false。
     * 背後是 Apify 的 adapter（Threads／Google Trends／Instagram）都應覆寫本方法，
     * 改用 {@link ApifyTokenProbe} 只驗證 token 與連線，不執行任何 actor。
     *
     * @return 探測成功回傳 true；任何例外一律視為失敗回傳 false，不向外拋出
     */
    default boolean probe() {
        try {
            fetch(List.of(PROBE_TARGET), LocalDate.now());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 查詢此來源目前的額度用量（單位見 {@link ApifyUsage}）。
     *
     * <p>不消耗爬取額度。查不到（未設定 token、Apify 無回應等）回傳 {@link Optional#empty()}，
     * 呼叫端保留資料庫既有數值。不是 Apify 來源的 adapter 不需要覆寫，預設就是查不到。
     */
    default Optional<ApifyUsage> fetchQuota() {
        return Optional.empty();
    }
}