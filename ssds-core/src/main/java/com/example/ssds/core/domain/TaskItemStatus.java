package com.example.ssds.core.domain;

/**
 * AI 任務逐項結果的狀態（規格書 §7.2.7 ai_task_item）。
 *
 * <p>刻意與 {@link TaskStatus} 分開：兩者的值域互有對方沒有的值，不是包含關係。
 * 子項目沒有 RUNNING／PARTIAL／CANCELLED——單一品項不會「部分成功」，
 * 取消是整批任務層級的動作；而 SKIPPED_* 只在子項目層級成立。
 */
public enum TaskItemStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    /** 命中快取而未送出請求，不佔用配額（FR-07 AC-07-3，計入 ai_task.cache_hit_count） */
    SKIPPED_CACHE,
    /** 該預算池配額耗盡而未送出，列入待重跑清單、隔日續跑（FR-07 AC-07-4） */
    SKIPPED_QUOTA
}
