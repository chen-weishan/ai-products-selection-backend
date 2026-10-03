package com.example.ssds.ingest.GoogleTrends;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class GoogleTrendsClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleTrendsClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 歷史回補專用的 maxItems 下限。每日排程沿用 properties（預設 30）即可，
     * 但回補的 timeframe 是一個月以上的逐日序列，30 筆會從最新那端被截掉，
     * 導致資料停在「起點 + 30 天」而不是今天。
     */
    private static final int BACKFILL_MIN_MAX_ITEMS = 400;
    private final RestClient restClient;
    private final GoogleTrendsIngestProperties properties;

    GoogleTrendsClient(RestClient googleTrendsRestClient, GoogleTrendsIngestProperties properties) {
        this.restClient = googleTrendsRestClient;
        this.properties = properties;
    }

    Long fetchLatestInterest(String keyword) {
        String json = restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/actors/cirkit~google-trends-scraper/run-sync-get-dataset-items")
                        .queryParam("token", properties.apifyToken())
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "keywords", List.of(keyword),
                        "geo", properties.geoOrDefault(),
                        "timeframe", properties.timeRangeOrDefault(),
                        "dataTypes", List.of("interestOverTime"),
                        "maxItems", properties.maxItemsOrDefault()))
                .retrieve()
                .body(String.class);

        List<TrendPoint> points = readValue(json);
        if (points == null || points.isEmpty()) {
            return null;
        }

        List<Integer> usableValues = points.stream()
                .filter(p -> !Boolean.TRUE.equals(p.isPartial()))
                .map(TrendPoint::value)
                .filter(v -> v != null)
                .toList();
        List<Integer> effectiveValues = usableValues.isEmpty()
                ? points.stream().map(TrendPoint::value).filter(v -> v != null).toList()
                : usableValues;
        if (effectiveValues.isEmpty()) {
            return null;
        }
        double average = effectiveValues.stream().mapToInt(Integer::intValue).average().orElse(0);
        return Math.round(average);
    }

    public List<DailyInterest> fetchInterestOverTime(String keyword, String timeframe) {
    String json = restClient.post()
            .uri(uriBuilder -> uriBuilder
                    .path("/actors/cirkit~google-trends-scraper/run-sync-get-dataset-items")
                    .queryParam("token", properties.apifyToken())
                    .build())
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of(
                    "keywords", List.of(keyword),
                    "geo", properties.geoOrDefault(),
                    "timeframe", timeframe,
                    "dataTypes", List.of("interestOverTime"),
                    "maxItems", Math.max(properties.maxItemsOrDefault(), BACKFILL_MIN_MAX_ITEMS)))
            .retrieve()
            .body(String.class);

    List<TrendPoint> points = readValue(json);
    if (points == null) {
        return List.of();
    }
    if (!points.isEmpty()) {
        log.info("Google Trends 回補原始回傳：keyword={} timeframe={} 共 {} 筆，第一筆 {}，最後一筆 {}",
                keyword, timeframe, points.size(), points.get(0), points.get(points.size() - 1));
    }

    return points.stream()
            .filter(p -> p.value() != null && p.date() != null && p.date().length() >= 10)
            // 不再過濾 isPartial：最近幾天未統計完的點也寫入，以取得最新日期。
            // 這些點的值之後可能被 Google 修正，重跑回補會以 upsert 覆蓋。
            .map(p -> new DailyInterest(LocalDate.parse(p.date().substring(0, 10)), p.value()))
            .toList();
}

    public record DailyInterest(LocalDate date, Integer value) {}

    private static List<TrendPoint> readValue(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, TrendPoint.class));
        } catch (Exception e) {
            throw new IllegalStateException("Apify google-trends-scraper 回應解析失敗", e);
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TrendPoint(
            String dataType,
            String keyword,
            String geo,
            String timeframe,
            String date,
            Integer value,
            Boolean isPartial) {}
}