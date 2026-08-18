package com.example.ssds.core.domain;

/**
 * 人工熱度標記的平台別（規格書 FR-14-1、§7.2 manual_heat_tag.platform）。
 *
 * <p>由使用者貼上的 URL 自動解析，可手動覆寫（AC-14-1）。
 * 系統<b>只儲存連結與評級，不擷取頁面內容</b>，避開重製與個資問題。
 */
public enum SocialPlatform {
    FACEBOOK,
    TIKTOK,
    XIAOHONGSHU,
    THREADS,
    INSTAGRAM,
    OTHER
}
