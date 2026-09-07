package com.example.ssds.ingest.Instagram;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Instagram hashtag 熱度串接設定（規格書未定義，實作擴充）。
 *
 * <p>對應 application.properties 的 {@code ssds.ingest.instagram.*}，
 * 實際值來自 .env 的 {@code SSDS_IG_RAPIDAPI_KEY}（命名沿用專案既有的
 * SSDS_* 慣例，見 CONTEXT.md §6）。
 *
 * <p><b>2026-09-01 改版：改用 RapidAPI 的第三方服務（instagram-social，
 * 資料源 steadyapi.com），不再打 Meta 官方 Graph API。</b>原因見
 * CONTEXT.md §7——Meta 官方版需要 App Review + Business Verification
 * 才能查任意 hashtag，個人開發者、沒有正式商業實體的情況下無法過審。
 * 認證方式也因此從「query string 帶 access_token + user_id」
 * 改成「header 帶 x-rapidapi-key」，不再需要 IG User ID。
 */
@ConfigurationProperties(prefix = "ssds.ingest.instagram")
public record InstagramIngestProperties(String rapidapiKey) {

    /** 未設定時視為此來源停用，避免每次呼叫都打出必敗的請求。 */
    public boolean configured() {
        return rapidapiKey != null && !rapidapiKey.isBlank();
    }
}