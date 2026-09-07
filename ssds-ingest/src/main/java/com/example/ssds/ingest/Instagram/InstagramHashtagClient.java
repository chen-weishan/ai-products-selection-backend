package com.example.ssds.ingest.Instagram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Apify 官方 actor「apify/instagram-hashtag-scraper」的包裝
 * ({@code POST /actors/apify~instagram-hashtag-scraper/run-sync-get-dataset-items?token=...}，
 * actor 名稱中的 {@code /} 在 URL 上要改寫成 {@code ~}，見 Apify API 慣例）。
 *
 * <p><b>熱度值改用「實際抓回的貼文篇數」，不是互動數（2026-09-07 實測後修正）。</b>
 * 原本設計是用 likesCount + commentsCount 總和，但實測 hashtag explore 頁
 * （{@code instagram.com/explore/tags/<tag>}）回傳的貼文，likesCount 與
 * commentsCount 全部固定是 0——Instagram 這個頁面底層的資料本來就不含
 * 互動數，不是 Apify 的問題，換哪一家爬蟲服務都一樣。因此改用貼文篇數：
 * 同樣的 resultsLimit 之下，熱門 hashtag 通常湊得滿，冷門的湊不滿，
 * 篇數本身就帶有相對熱度的訊號，雖然比互動數粗略，但至少不是恆為 0。
 *
 * <p>回應改用 {@link RestClient#retrieve()} 取原始 JSON 字串、再以
 * {@link ObjectMapper} 手動解析（比照 {@code TrendService} 的既有作法，
 * 理由同 CONTEXT.md §7 之前記錄的 Jackson 2/3 版本不確定性說明）。
 */
@Component
class InstagramHashtagClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient;
    private final InstagramIngestProperties properties;

    InstagramHashtagClient(RestClient instagramRestClient, InstagramIngestProperties properties) {
        this.restClient = instagramRestClient;
        this.properties = properties;
    }

    /**
     * 查詢 hashtag 最近的貼文，回傳實際抓回的篇數作為熱度原始值。
     * 查無任何貼文時回傳 null，由呼叫端（adapter）決定要不要跳過。
     */
    Long fetchPostCount(String hashtag) {
        String json = restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/actors/apify~instagram-hashtag-scraper/run-sync-get-dataset-items")
                        .queryParam("token", properties.apifyToken())
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "hashtags", List.of(hashtag),
                        "resultsLimit", properties.resultsLimitOrDefault()))
                .retrieve()
                .body(String.class);

        List<Post> posts = readValue(json);
        if (posts == null || posts.isEmpty()) {
            return null;
        }
        return (long) posts.size();
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
            throw new IllegalStateException("Apify instagram-hashtag-scraper 回應解析失敗", e);
        }
    }

    // 只需要知道有幾筆，欄位本身用不到，仍宣告一個空 record 讓 Jackson 能反序列化陣列。
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Post() {}
}
