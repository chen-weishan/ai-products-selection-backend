package com.example.ssds.ingest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * FR-14-2 輕量探測：只驗證 Apify token 是否有效，不執行任何 actor、不消耗爬取額度。
 *
 * <p>呼叫 {@code GET https://api.apify.com/v2/users/me}（Apify 官方「取得使用者資料」端點），
 * 回 200 代表 token 有效且 Apify 可連線；其餘（401、逾時、連不上）一律視為探測失敗。
 *
 * <p>放在 ssds-ingest 模組，三個 adapter（Threads／Google Trends／Instagram）共用。
 */
public final class ApifyTokenProbe {

    private static final URI ME = URI.create("https://api.apify.com/v2/users/me");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private ApifyTokenProbe() {
    }

    /** token 為空白直接回 false（等同「未設定」），不發出請求。 */
    public static boolean tokenIsValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(ME)
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            int status = HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}