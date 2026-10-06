package com.example.ssds.api.heat;

/**
 * 使用者編輯了一筆人工熱度標記（FR-14-1）：熱度等級、觀察時間都可能改變當日讀值。
 *
 * <p>由 {@code ManualHeatTagCommandService.update} 發布；監聽端須用 AFTER_COMMIT。
 */
public record ManualHeatTagUpdatedEvent(Long tagId) {}