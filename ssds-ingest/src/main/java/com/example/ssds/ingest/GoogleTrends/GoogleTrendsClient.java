package com.example.ssds.ingest.GoogleTrends;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Apify 第三方 actor「santhej/google-trends-scraper」的包裝。
 *
 * <p>Google Trends 本身就是 0–100 的相對指數，這裡直接取該 actor 回傳的
 * 「最新一筆 interest 數值」當熱度原始值，不用像 Instagram/Threads 那樣
 * 用貼文篇數當代理值。
 *
 * <p><b>此 actor 的確切回應欄位尚未經過真實 token 實測確認。</b>目前依
 * 官方文件描述的「interest-over-time per keyword」推斷欄位名稱
 * （{@code latestInterest}／{@code averageInterest}），實際欄位對不上時，
 * 需要用真實 token 跑一次確認 schema 後調整（比照 Instagram 2026-09-07
 * 那次的修正模式）。
 */
@Component
class GoogleTrendsClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient;
    private final GoogleTrendsIngestProperties properties;

    GoogleTrendsClient(RestClient googleTrendsRestClient, GoogleTrendsIngestProperties properties) {
        this.restClient = googleTrendsRestClient;
        this.properties = properties;
    }

    /** 查詢關鍵字最新的 Google Trends 相對熱度指數（0–100）。查無資料回傳 null。 */
    Long fetchLatestInterest(String keyword) {
        String json = restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/actors/santhej~google-trends-scraper/run-sync-get-dataset-items")
                        .queryParam("token", properties.apifyToken())
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "keywords", List.of(keyword),
                        "timeRange", properties.timeRangeOrDefault(),
                        "trendType", "web"))
                .retrieve()
                .body(String.class);

        List<TrendResult> results = readValue(json);
        if (results == null || results.isEmpty()) {
            return null;
        }
        TrendResult result = results.get(0);
        Integer value = result.latestInterest() != null ? result.latestInterest() : result.averageInterest();
        return value != null ? value.longValue() : null;
    }

    private static List<TrendResult> readValue(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, TrendResult.class));
        } catch (Exception e) {
            throw new IllegalStateException("Apify google-trends-scraper 回應解析失敗", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TrendResult(
            String keyword,
            @JsonProperty("average_interest") Integer averageInterest,
            @JsonProperty("peak_interest") Integer peakInterest,
            @JsonProperty("latest_interest") Integer latestInterest,
            @JsonProperty("time_range") String timeRange,
            List<SeriesPoint> series) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeriesPoint(String date, Integer value) {}
}