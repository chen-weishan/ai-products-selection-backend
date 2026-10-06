package com.example.ssds.api.sourcing.dto;

import java.util.List;

public record SourcingQueueResponse(
        List<SourcingQueueItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        SourcingQueueSummaryResponse summary) {
}
