package com.example.ssds.ingest.Threads;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Apify 第三方 actor「futurizerush/meta-threads-scraper-zh-tw」的包裝
 * ({@code POST /actors/futurizerush~meta-threads-scraper-zh-tw/run-sync-get-dataset-items?token=...}，
 * actor 名稱中的 {@code /} 在 URL 上要改寫成 {@code ~}，見 Apify API 慣例）。
 *
 * <p>2026-09-11 改版：原本串接的 easyapi/threads-search-scraper 改為此 actor。
 * 輸入格式跟舊 actor 不同：需多帶 {@code mode=search}，排序欄位改名為
 * {@code search_filter}（值為 {@code top}/{@code recent}），篇數上限欄位改名為
 * {@code max_posts}。
 *
 * <p>熱度值改採「讚+留言+轉發+引用+分享」加總（舊版是單純算搜到的篇數）。
 * 新 actor 的 output schema 有完整互動數欄位，比篇數更能反映真實熱度，
 * 且已排除轉發／回覆列（見 {@code record_type}／{@code is_repost}／
 * {@code is_reply}），避免這些非原創內容灌水。完整欄位表見
 * {@link Post} 的說明。
 */
@Component
class ThreadsSearchClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient;
    private final ThreadsIngestProperties properties;

    ThreadsSearchClient(RestClient threadsRestClient, ThreadsIngestProperties properties) {
        this.restClient = threadsRestClient;
        this.properties = properties;
    }

    /** 查詢關鍵字最近的貼文，回傳互動熱度加總（讚+留言+轉發+引用+分享）。查無資料回傳 null。 */
    Long fetchEngagementHeat(String keyword) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("mode", "search");
        requestBody.put("keywords", List.of(keyword));
        requestBody.put("search_filter", properties.searchFilterOrDefault());
        requestBody.put("max_posts", properties.resultsLimitOrDefault());
        requestBody.put("start_date", properties.startDateParam());
        properties.endDateParam().ifPresent(endDate -> requestBody.put("end_date", endDate));

        String json = restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/actors/futurizerush~meta-threads-scraper-zh-tw/run-sync-get-dataset-items")
                        .queryParam("token", properties.apifyToken())
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        List<Post> posts = readValue(json);
        if (posts == null || posts.isEmpty()) {
            return null;
        }

        // 2026-09-14 發現：search_filter=recent 時日期雖然準（只落在很近的範圍），
        // 但常常直接搜不到任何結果（懷疑是 Threads 平台「最新」搜尋索引視窗本來
        // 就很短，不是 actor 或參數的問題）；search_filter=top 資料量足夠，但
        // actor 的 start_date/end_date 對 top 排序似乎沒有確實生效，日期會跨到
        // 數週前。兩者取捨後決定：固定用 top 拿資料量，日期窗口自己用
        // created_at 在這裡精準過濾，不依賴 actor 端的日期參數是否生效。
        //
        // record_type 會混雜原創貼文／轉發／回覆，只算原創貼文；另外 actor
        // 回傳的「關鍵字搜尋結果」不保證真的包含關鍵字，所以也自己比對一次。
        long heat = posts.stream()
                .filter(p -> !Boolean.TRUE.equals(p.isRepost()) && !Boolean.TRUE.equals(p.isReply()))
                .filter(p -> containsKeyword(p.textContent(), keyword))
                .filter(p -> withinLookbackWindow(p.createdAt()))
                .mapToLong(Post::engagementTotal)
                .sum();
        return heat == 0 ? null : heat;
    }

    private static boolean containsKeyword(String textContent, String keyword) {
        if (textContent == null || keyword == null) {
            return false;
        }
        return textContent.toLowerCase(java.util.Locale.ROOT).contains(keyword.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * created_at 是 UTC 時間字串（例如 {@code 2026-09-11T02:08:20.123Z}），
     * 只取日期部分跟 lookbackDays／endLookbackDays 算出的窗口比較。
     * 窗口同樣以 UTC 日曆日計算，跟 created_at 的時區一致，避免時區位移
     * 讓邊界日期算錯（代價是跟台北時間的「今天」會差最多 8 小時的模糊帶，
     * 現階段抓天數為單位、不追求到小時精度，可接受）。
     */
    private boolean withinLookbackWindow(String createdAt) {
        if (createdAt == null || createdAt.length() < 10) {
            return false;
        }
        java.time.LocalDate postDate;
        try {
            postDate = java.time.LocalDate.parse(createdAt.substring(0, 10));
        } catch (Exception e) {
            return false;
        }
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.time.LocalDate windowStart = today.minusDays(properties.lookbackDaysOrDefault());
        java.time.LocalDate windowEnd = today.minusDays(properties.endLookbackDaysOrDefault());
        return !postDate.isBefore(windowStart) && !postDate.isAfter(windowEnd);
    }

    private static List<Post> readValue(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Post.class));
        } catch (Exception e) {
            throw new IllegalStateException("Apify meta-threads-scraper-zh-tw 回應解析失敗", e);
        }
    }

    /**
     * 對應官方 output schema（節錄與熱度計算相關的欄位；完整欄位表見
     * https://apify.com/futurizerush/meta-threads-scraper-zh-tw/output-schema）：
     *
     * <pre>
     * record_type          類型（post／repost／reply 等）
     * post_code            貼文代碼
     * username             用戶名稱
     * user_id              帳號 ID
     * display_name         顯示名稱
     * text_content         內容
     * created_at           發布時間
     * created_at_display   日期（UTC）
     * like_count           按讚
     * reply_count          留言數
     * repost_count         轉發
     * quote_count          引用
     * share_count          分享
     * view_count           觀看
     * view_count_status    觀看狀態
     * is_reply             是留言
     * is_quote_post        是引用
     * is_repost            是轉發
     * followers_count      作者追蹤者數
     * is_verified          作者已驗證
     * post_url             網址
     * search_keyword       關鍵字
     * search_filter        排序
     * </pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Post(
            @JsonProperty("record_type") String recordType,
            @JsonProperty("text_content") String textContent,
            @JsonProperty("like_count") Long likeCount,
            @JsonProperty("reply_count") Long replyCount,
            @JsonProperty("repost_count") Long repostCount,
            @JsonProperty("quote_count") Long quoteCount,
            @JsonProperty("share_count") Long shareCount,
            @JsonProperty("view_count") Long viewCount,
            @JsonProperty("is_reply") Boolean isReply,
            @JsonProperty("is_repost") Boolean isRepost,
            @JsonProperty("is_quote_post") Boolean isQuotePost,
            @JsonProperty("created_at") String createdAt) {

        long engagementTotal() {
            return nz(likeCount) + nz(replyCount) + nz(repostCount) + nz(quoteCount) + nz(shareCount);
        }

        private static long nz(Long v) {
            return v == null ? 0L : v;
        }
    }
}