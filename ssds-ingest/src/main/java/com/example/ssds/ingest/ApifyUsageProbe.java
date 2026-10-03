package com.example.ssds.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * 讀取 Apify 帳號本月用量與上限，讓 S-16 的「額度用量」欄反映 Apify 後台的真實數字。
 *
 * <p>呼叫 {@code GET https://api.apify.com/v2/users/me/limits}（與 Apify Console
 * 「Limits」頁同一份資料）：{@code data.current.monthlyUsageUsd} 是本月已用金額，
 * {@code data.limits.maxMonthlyUsageUsd} 是本月上限，換月時 Apify 自己重置，不需要另外歸零。
 * 這個端點只讀帳號資料，不執行任何 actor，不消耗爬取額度。
 *
 * <p>任何失敗（token 空白、401、逾時、格式不符）一律回 {@link Optional#empty()}，
 * 呼叫端沿用資料庫既有數值，不因為查不到用量而中斷測試連線或採集。
 */
public final class ApifyUsageProbe {

    private static final URI LIMITS = URI.create("https://api.apify.com/v2/users/me/limits");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApifyUsageProbe() {
    }

    public static Optional<ApifyUsage> fetch(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(LIMITS)
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 拆出來讓單元測試可以直接餵 JSON，不必真的連 Apify。 */
    static Optional<ApifyUsage> parse(String json) {
        try {
            JsonNode data = MAPPER.readTree(json).path("data");
            JsonNode used = data.path("current").path("monthlyUsageUsd");
            if (!used.isNumber()) {
                return Optional.empty();
            }
            JsonNode limit = data.path("limits").path("maxMonthlyUsageUsd");
            Integer limitCents = limit.isNumber() ? toCents(limit.asDouble()) : null;
            return Optional.of(new ApifyUsage(toCents(used.asDouble()), limitCents));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static int toCents(double usd) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, Math.round(usd * 100)));
    }
}
