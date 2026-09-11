package com.example.ssds.ingest.Threads;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Apify 第三方 actor「easyapi/threads-search-scraper」的包裝
 * ({@code POST /actors/easyapi~threads-search-scraper/run-sync-get-dataset-items?token=...}，
 * actor 名稱中的 {@code /} 在 URL 上要改寫成 {@code ~}，見 Apify API 慣例）。
 *
 * <p>熱度值採「實際搜到的貼文篇數」，理由同 Instagram（見
 * InstagramHashtagClient 類別註解）：比互動數更不容易查無/固定為 0，
 * 篇數本身仍帶有相對熱度訊號。
 *
 * <p><b>此 actor 的確切回應欄位尚未經過真實 token 實測確認。</b>目前只取
 * 陣列長度、不解析個別欄位，所以欄位命名對不對不影響運作；但如果之後
 * 想解析互動數等細節，需要先用真實 token 實測一次確認 schema（比照
 * Instagram 2026-09-07 那次的修正模式）。
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

    /** 查詢關鍵字最近的貼文，回傳實際抓回的篇數。查無資料回傳 null。 */
    Long fetchPostCount(String keyword) {
        String json = restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/actors/easyapi~threads-search-scraper/run-sync-get-dataset-items")
                        .queryParam("token", properties.apifyToken())
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "keywords", List.of(keyword),
                        "sortBy", "top",
                        "maxPostsPerKeyword", properties.resultsLimitOrDefault()))
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
            throw new IllegalStateException("Apify threads-search-scraper 回應解析失敗", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Post() {}
}