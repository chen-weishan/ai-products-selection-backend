package com.example.ssds.core.domain;

/**
 * AI 任務與匯入批次的執行狀態（規格書 §7.2.7 ai_task、§7.2.11 import_batch）。
 *
 * <p>ai_task_item 用 {@link TaskItemStatus}，值域不同，見該 enum 的說明。
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** 部分成功：匯入時部分列失敗仍寫入正確列（FR-09） */
    PARTIAL,
    CANCELLED
}
