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
public record ThreadsIngestProperties(String apifyToken, Integer resultsLimit) {

    private static final int DEFAULT_RESULTS_LIMIT = 50;

    /** 未設定時視為此來源停用，避免每次呼叫都打出必敗的請求。 */
    public boolean configured() {
        return apifyToken != null && !apifyToken.isBlank();
    }

    public int resultsLimitOrDefault() {
        return resultsLimit != null ? resultsLimit : DEFAULT_RESULTS_LIMIT;
    }
}