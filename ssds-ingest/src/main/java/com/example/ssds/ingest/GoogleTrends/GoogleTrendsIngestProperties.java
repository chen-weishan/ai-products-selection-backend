package com.example.ssds.ingest.GoogleTrends;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google Trends 熱度串接設定。規格書本身承認 Google Trends 沒有官方 API
 * （附錄A第2項待確認事項），改用 Apify 第三方 actor。
 *
 * <p>對應 application.properties 的 {@code ssds.ingest.google-trends.*}，
 * 實際值來自 .env 的 {@code SSDS_TRENDS_APIFY_TOKEN}。
 */
@ConfigurationProperties(prefix = "ssds.ingest.google-trends")
public record GoogleTrendsIngestProperties(String apifyToken, String timeRange, String geo, Integer maxItems) {

    private static final String DEFAULT_TIME_RANGE = "now 1-d";
    private static final String DEFAULT_GEO = "TW";
    private static final int DEFAULT_MAX_ITEMS = 30;

    public boolean configured() {
        return apifyToken != null && !apifyToken.isBlank();
    }

    public String timeRangeOrDefault() {
        return (timeRange == null || timeRange.isBlank()) ? DEFAULT_TIME_RANGE : timeRange;
    }

    public String geoOrDefault() {
        return (geo == null || geo.isBlank()) ? DEFAULT_GEO : geo;
    }

    public int maxItemsOrDefault() {
        return maxItems != null ? maxItems : DEFAULT_MAX_ITEMS;
    }
}