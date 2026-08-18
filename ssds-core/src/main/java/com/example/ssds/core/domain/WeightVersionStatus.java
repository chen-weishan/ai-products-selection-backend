package com.example.ssds.core.domain;

/**
 * 權重版本狀態（規格書 FR-08 版本管理、§7.2 weight_version.status）。
 *
 * <p>AC-08-2：生效中（ACTIVE）的版本不可編輯，只能建立新版本；
 * 每筆評分紀錄 weight_version_id，可還原當時的權重設定（AC-08-4）。
 */
public enum WeightVersionStatus {
    DRAFT,
    ACTIVE,
    RETIRED
}
