package com.example.ssds.ingest.Threads;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Threads 熱度串接設定。規格書要求官方 API `/keyword_search`（需 Meta 審核，
 * 額度 2,200 查詢/24小時），但團隊決定暫不走官方審核，比照 Instagram 改用
 * Apify 第三方 scraper actor（見 application.properties 的 Instagram 區塊
 * 2026-09-01 改版說明：官方 App Review／Business Verification 過不了）。
 * 這是已知偏離規格書附錄C的做法。
 *
 * <p>對應 application.properties 的 {@code ssds.ingest.threads.*}，
 * 實際值來自 .env 的 {@code SSDS_THREADS_APIFY_TOKEN}。
 */
@ConfigurationProperties(prefix = "ssds.ingest.threads")
public record ThreadsIngestProperties(
        String apifyToken,
        Integer resultsLimit,
        Integer lookbackDays,
        Integer endLookbackDays,
        String searchFilter) {

    private static final int DEFAULT_RESULTS_LIMIT = 50;
    // 只抓最近幾天的貼文，避免熱度值被跨月的舊爆文洗掉。這個窗口現在是在
    // ThreadsSearchClient 用 created_at 自己過濾出來的，不是靠 actor 的
    // start_date/end_date（2026-09-14 確認那兩個參數對 search_filter=top
    // 排序沒有確實生效；search_filter=recent 雖然日期準，但常常直接搜不到
    // 結果——懷疑是 Threads 平台「最新」搜尋索引視窗本身就很短，不是參數
    // 沒設對）。預設 3 天，跟每日採集頻率留一點緩衝。
    private static final int DEFAULT_LOOKBACK_DAYS = 3;
    // 固定用 top 才有穩定資料量，recent 常常搜不到東西。
    private static final String DEFAULT_SEARCH_FILTER = "top";

    /** 未設定時視為此來源停用，避免每次呼叫都打出必敗的請求。 */
    public boolean configured() {
        return apifyToken != null && !apifyToken.isBlank();
    }

    public int resultsLimitOrDefault() {
        return resultsLimit != null ? resultsLimit : DEFAULT_RESULTS_LIMIT;
    }

    public int lookbackDaysOrDefault() {
        return lookbackDays != null ? lookbackDays : DEFAULT_LOOKBACK_DAYS;
    }

    /** 窗口上界（幾天前算到現在），供 ThreadsSearchClient 用 created_at 過濾。未設定＝0＝到今天為止。 */
    public int endLookbackDaysOrDefault() {
        return endLookbackDays != null ? endLookbackDays : 0;
    }

    /**
     * actor 的 start_date，一樣送過去當作輸入端的粗略提示（減少 actor 端要
     * 爬取、比對的量），但不再依賴它精準生效——真正的窗口由
     * {@link ThreadsSearchClient} 用 {@code created_at} 二次過濾決定。
     */
    public String startDateParam() {
        return lookbackDaysOrDefault() + " days";
    }

    /** actor 的 end_date，同上，只當作粗略提示，不依賴它精準生效。 */
    public java.util.Optional<String> endDateParam() {
        return endLookbackDays == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(endLookbackDays + " days");
    }

    /** actor 的 search_filter："top"（熱門）或 "recent"（最新）。 */
    public String searchFilterOrDefault() {
        return searchFilter != null && !searchFilter.isBlank() ? searchFilter : DEFAULT_SEARCH_FILTER;
    }
}