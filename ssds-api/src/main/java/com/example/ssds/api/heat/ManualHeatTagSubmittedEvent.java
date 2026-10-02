package com.example.ssds.api.heat;

/**
 * 使用者送出（新增）了一筆人工熱度標記（FR-14-1）。
 *
 * <p>由 {@code ManualHeatTagCommandService.create} 在標記寫入後發布；
 * 監聽端須用 AFTER_COMMIT，確保探測時已看得到這筆新標記。
 */
public record ManualHeatTagSubmittedEvent(Long tagId) {}