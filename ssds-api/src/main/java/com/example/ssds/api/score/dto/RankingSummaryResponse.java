package com.example.ssds.api.score.dto;

/** 排行篩選條件下的全榜分級分布；不是目前分頁的統計。 */
public record RankingSummaryResponse(
        long totalCount,
        long gradeACount,
        long gradeBCount,
        long gradeCCount) {}
