package com.example.ssds.api.heat;

/**
 * 使用者刪除了一筆人工熱度標記（FR-14-1）。
 *
 * <p>由 {@code ManualHeatTagCommandService.delete} 在標記刪除後發布；
 * 監聽端須用 AFTER_COMMIT，確保探測時這筆標記已不在資料庫。
 */
public record ManualHeatTagDeletedEvent(Long tagId) {}