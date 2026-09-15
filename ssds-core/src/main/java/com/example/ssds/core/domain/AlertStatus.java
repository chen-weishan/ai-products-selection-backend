package com.example.ssds.core.domain;

/**
 * 風險示警處理狀態（規格書 §7.2 risk_alert.status）。
 *
 * <p>AC-10-2：已 IGNORED 的示警不再出現於預設清單，但可用篩選查回來 ——
 * 所以「預設查詢」與「全部查詢」是兩支方法，不是一支加旗標。
 */
public enum AlertStatus {
    OPEN,
    ACKNOWLEDGED,
    IGNORED
}
