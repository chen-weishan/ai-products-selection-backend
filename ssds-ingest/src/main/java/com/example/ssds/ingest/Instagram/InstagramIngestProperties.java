package com.example.ssds.ingest.Instagram;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Instagram hashtag 熱度串接設定（規格書未定義，實作擴充）。
 *
 * <p>對應 application.properties 的 {@code ssds.ingest.instagram.*}，
 * 實際值來自 .env 的 {@code SSDS_IG_APIFY_TOKEN}（命名沿用專案既有的
 * SSDS_* 慣例，見 CONTEXT.md §6）。
 *
 * <p><b>2026-09-07 改版：改用 Apify 平台的官方 actor
 * {@code apify/instagram-hashtag-scraper}，不再打 RapidAPI 的
 * instagram-social。</b>認證方式從「header 帶 x-rapidapi-key」改成
 * 「query string 帶 token=」（Apify 慣例）。新增 {@code resultsLimit}：
 * 這支 actor 是「實際爬貼文」而非「查總數」，每查一次 hashtag 就會
 * 消耗與抓回貼文數成正比的用量，這個值同時決定成本與熱度樣本數，
 * 見 {@link InstagramHashtagClient} 的熱度換算說明。
 */
@ConfigurationProperties(prefix = "ssds.ingest.instagram")
public record InstagramIngestProperties(String apifyToken, Integer resultsLimit) {

    private static final int DEFAULT_RESULTS_LIMIT = 20;

    /** 未設定時視為此來源停用，避免每次呼叫都打出必敗的請求。 */
    public boolean configured() {
        return apifyToken != null && !apifyToken.isBlank();
    }

    /** 未設定時採用預設值，避免每個 hashtag 都要求呼叫端手動帶入。 */
    public int resultsLimitOrDefault() {
        return resultsLimit != null ? resultsLimit : DEFAULT_RESULTS_LIMIT;
    }
}
