package com.example.ssds.ingest.Instagram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RapidAPI「instagram-social」的 hashtag 搜尋端點包裝
 * ({@code GET /api/v1/instagram/hashtags/search?search=關鍵字})。
 *
 * <p>回應是「搜尋這個關鍵字，模糊比對出來的一批相關 hashtag」，不是
 * 「這個 hashtag 本身的精確資料」——例如查 {@code chocolate} 會連
 * {@code chocolatecake}、{@code chocolatelover} 都一起列出來。因此拿到
 * 結果後要在 {@code body} 陣列裡找 {@code name} 完全等於查詢字串的那筆
 * （忽略大小寫），取它的 {@code media_count}（該 hashtag 的總貼文數，
 * 作為熱度原始值）。查無完全相符的項目視為查不到，不強行用最相近的
 * 那筆湊數。
 *
 * <p>回應改用 {@link RestClient#retrieve()} 取原始 JSON 字串、再以
 * {@link ObjectMapper} 手動解析（比照 {@code TrendService} 的既有作法），
 * 理由同 CONTEXT.md §7 之前記錄的 Jackson 2/3 版本不確定性說明。
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
     * 查詢 hashtag 的總貼文數（{@code media_count}），作為熱度原始值。
     * 查無完全相符的 hashtag 時回傳 null，由呼叫端（adapter）決定要不要跳過。
     */
    Long fetchMediaCount(String hashtag) {
        String json = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/instagram/hashtags/search")
                        .queryParam("search", hashtag)
                        .build())
                .header("x-rapidapi-key", properties.rapidapiKey())
                .header("x-rapidapi-host", "instagram-social.p.rapidapi.com")
                .retrieve()
                .body(String.class);

        SearchResponse response = readValue(json);
        if (response == null || response.body() == null) {
            return null;
        }
        return response.body().stream()
                .filter(tag -> hashtag.equalsIgnoreCase(tag.name()))
                .map(Tag::mediaCount)
                .findFirst()
                .orElse(null);
    }

    private static SearchResponse readValue(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SearchResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("instagram-social API 回應解析失敗", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchResponse(List<Tag> body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Tag(String name, @JsonProperty("media_count") Long mediaCount) {}
}