package com.example.ssds.api.weight.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.example.ssds.core.domain.WeightVersionStatus;

public record WeightVersionSummaryResponse(
        Long id,
        String versionNo,
        String name,
        WeightVersionStatus status,
        boolean isCurrent,
        LocalDate effectiveFrom,
        String changeNote,
        OffsetDateTime createdAt,
        OffsetDateTime approvedAt) {

}
