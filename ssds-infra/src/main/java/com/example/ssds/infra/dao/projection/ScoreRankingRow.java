package com.example.ssds.infra.dao.projection;

import java.math.BigDecimal;

/**
 * 排行榜單列（FR-04 選品分數排行）。
 *
 * <p>用 record 而非 Entity：排行頁需要的是跨表的扁平欄位（品項名、類別名、
 * 分數、分級…），撈 Entity 回來還得逐一走關聯，正是 §7.3 要避免的 N+1。
 * DTO projection 一次 SQL 就把畫面要的東西補齊。
 */
public record ScoreRankingRow(
        Long scoreId,
        Long productId,
        String productName,
        Long categoryId,
        String categoryName,
        String sceneType,
        BigDecimal bonusSubtotal,
        BigDecimal penaltySubtotal,
        BigDecimal finalScore,
        String grade,
        int confidence,
        boolean lowConfidence) {}
