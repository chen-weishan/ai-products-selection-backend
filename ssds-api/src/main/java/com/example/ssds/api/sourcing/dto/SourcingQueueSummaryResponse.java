package com.example.ssds.api.sourcing.dto;

public record SourcingQueueSummaryResponse(
        long activeCount,
        long rejectedCount,
        long promotedCount) {
}
