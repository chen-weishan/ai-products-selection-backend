package com.example.ssds.core.domain;

/**
 * 熱度曲線階段（規格書 §5.8、§7.2 sourcing_candidate.heat_stage）。
 *
 * <p>各階段對應的「預估熱度剩餘壽命」初始經驗值定義於 §5.8，
 * 高原期需再區分第 1–2 週（42 天）與第 3 週以上（35 天），
 * 因此壽命推估需同時參考本欄位與 stage_weeks。
 */
public enum HeatStage {
    /** 上升期，初始推估 56 天 */
    RISING,
    /** 高原期，初始推估 42 天（第 1–2 週）／35 天（第 3 週以上） */
    PLATEAU,
    /** 衰退期，初始推估 17 天 */
    DECLINING
}
