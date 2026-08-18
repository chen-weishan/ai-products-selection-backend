package com.example.ssds.core.domain;

/**
 * 風險示警處理狀態（規格書 §7.2 risk_alert.status）。
 *
 * <p>AC-10-2：已 IGNORED 的示警不再出現於預設清單，但可用篩選查看，
 * 因此不做實體刪除。忽略時必須填 ignore_reason。
 */
public enum AlertStatus {
    OPEN,
    ACKNOWLEDGED,
    IGNORED
}
