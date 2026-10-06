package com.example.ssds.infra.dao.projection;

public record SourcingQueueCounts(
        long totalElements,
        long activeCount,
        long rejectedCount,
        long promotedCount) {
}
