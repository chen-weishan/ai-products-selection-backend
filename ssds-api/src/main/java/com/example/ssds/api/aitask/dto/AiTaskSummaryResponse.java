package com.example.ssds.api.aitask.dto;

public record AiTaskSummaryResponse(
        long runningCount,
        long monthlyCompletedCount,
        long failedCount) {
}
