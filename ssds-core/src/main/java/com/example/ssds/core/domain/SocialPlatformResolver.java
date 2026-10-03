package com.example.ssds.core.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 由來源連結的網域自動判定 {@link SocialPlatform}（規格書 FR-14-1、AC-14-1）。
 *
 * <p>「貼上後自動解析平台別」——使用者只需要貼連結，平台別由本類別猜，
 * 猜錯時前端仍可手動覆寫（{@code manual_heat_tag.platform} 沒有唯讀限制）。
 * 因此這裡刻意用寬鬆的網域字串比對，猜錯的代價很低（一次下拉選單修正），
 * 比起要求嚴謹的網域白名單、猜不出就整個表單卡住，更符合「30 秒內填完」的目標。
 */
public final class SocialPlatformResolver {

    private SocialPlatformResolver() {}

    public static SocialPlatform resolve(String url) {
        if (url == null || url.isBlank()) {
            return SocialPlatform.OTHER;
        }

        String host = extractHost(url);
        if (host == null) {
            return SocialPlatform.OTHER;
        }
        host = host.toLowerCase(Locale.ROOT);

        if (host.endsWith("facebook.com") || host.endsWith("fb.com") || host.endsWith("fb.watch")) {
            return SocialPlatform.FACEBOOK;
        }
        if (host.endsWith("tiktok.com")) {
            return SocialPlatform.TIKTOK;
        }
        if (host.endsWith("xiaohongshu.com") || host.endsWith("xhslink.com")) {
            return SocialPlatform.XIAOHONGSHU;
        }
        if (host.endsWith("threads.net") || host.endsWith("threads.com")) {
            return SocialPlatform.THREADS;
        }
        if (host.endsWith("instagram.com")) {
            return SocialPlatform.INSTAGRAM;
        }
        return SocialPlatform.OTHER;
    }

    /**
     * 解析網址取得 host。使用者貼上的連結不一定帶 scheme（例如直接貼
     * {@code www.instagram.com/p/xxx} 而非 {@code https://...}），此時
     * {@link URI} 會把整段路徑誤判為 path、host 為 null——退而求其次，
     * 補上 {@code https://} 前綴再試一次。
     */
    private static String extractHost(String url) {
        String trimmed = url.trim();
        String host = tryParseHost(trimmed);
        if (host != null) {
            return host;
        }
        return tryParseHost("https://" + trimmed);
    }

    private static String tryParseHost(String url) {
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
