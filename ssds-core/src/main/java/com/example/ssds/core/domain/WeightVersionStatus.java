package com.example.ssds.core.domain;

/**
 * 權重版本狀態（規格書 FR-08 版本管理、§7.2 weight_version.status）。
 *
 * <p>AC-08-2：已核准（APPROVED）的版本不可編輯，只能建立新版本；
 * 每筆評分紀錄 weight_version_id，可還原當時的權重設定（AC-08-4）。
 *
 * <p>v2.0 的 {@code ACTIVE} 於 v3.0 更名為 {@code APPROVED}（§7.2.5）。
 * 更名同時把兩件本來混在一起的事分開了：「已核准」是狀態，
 * 「現在生效中」是 {@code weight_version.is_current} 旗標。
 * 同一版可以核准後先不生效；資料庫端以 partial unique index
 * 保證同時只有一筆 is_current。
 */
public enum WeightVersionStatus {
    DRAFT,
    APPROVED,
    RETIRED
}
