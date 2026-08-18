package com.example.ssds.core.domain;

/** AI 任務與匯入批次的執行狀態（規格書 §7.2 ai_task / ai_task_item / import_batch）。 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    /** 部分成功：匯入時部分列失敗仍寫入正確列（FR-09） */
    PARTIAL,
    CANCELLED
}
