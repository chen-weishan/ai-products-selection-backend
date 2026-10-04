package com.example.ssds.api.weight.dto;

import com.example.ssds.core.domain.SceneType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /weight-versions/{id}/scene-stats}：該版本各情境的 AI 判定數與人工覆寫率
 * （規格書 §8.2 權重版本，v3.0 補入；畫面 S-09「AI 選組規則卡」）。
 *
 * <p>統計單位是<b>品項</b>：生效區間內每個品項只取最新一筆判定紀錄，
 * 與 {@code SceneOverrideLookup}「最新一筆才算數」同一規則。
 *
 * @param windowFrom      生效區間起日（含，台北日期）；草稿版本為 null
 * @param windowTo        生效區間終日（不含）＝下一版生效日；生效中版本為 null（至今）
 * @param judgedCount     區間內有判定紀錄的品項數
 * @param overriddenCount 其中最新一筆為人工覆寫者
 * @param overrideRate    覆寫率，0–1、4 位小數；分母 0 時為 null
 * @param aiFailedCount   最新一筆 {@code ai_scene_type} 為 NULL（AI 判定失敗）者，不歸入任何情境
 * @param scenes          固定四列，依 {@link SceneType} 宣告順序，沒有資料也列出（數值為 0）
 */
public record SceneStatsResponse(
        Long weightVersionId,
        LocalDate windowFrom,
        LocalDate windowTo,
        long judgedCount,
        long overriddenCount,
        BigDecimal overrideRate,
        long aiFailedCount,
        List<SceneStat> scenes) {

    /**
     * @param aiJudgedCount   AI 判為此情境的品項數（依 {@code ai_scene_type} 分組）
     * @param overriddenCount 其中被人工覆寫者
     * @param overrideRate    此情境的覆寫率，0–1；分母 0 時為 null
     * @param finalCount      最終採用此情境的品項數（依 {@code final_scene_type}），即「每組品項數」
     */
    public record SceneStat(
            SceneType sceneType,
            long aiJudgedCount,
            long overriddenCount,
            BigDecimal overrideRate,
            long finalCount) {
    }
}
