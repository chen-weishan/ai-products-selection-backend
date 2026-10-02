package com.example.ssds.api.heat;

/**
 * 使用者刪除了一筆人工熱度標記（FR-14-1）。
 *
 * <p>由 {@code ManualHeatTagCommandService.delete} 在標記刪除後發布；
 * 監聽端須用 AFTER_COMMIT，確保探測時這筆標記已不在資料庫。
 *
 * <p>標記刪除後就查不到它綁的標的了，所以事件要自帶 {@code productId}／{@code keywordId}
 * （二擇一非 null），讓監聽端知道該重算哪些關鍵字的熱度讀值。
 */
public record ManualHeatTagDeletedEvent(Long tagId, Long productId, Long keywordId) {

    /** 只關心「有標記被刪」、不需要標的的監聽端（例如 MANUAL 來源健康檢查）。 */
    public ManualHeatTagDeletedEvent(Long tagId) {
        this(tagId, null, null);
    }
}