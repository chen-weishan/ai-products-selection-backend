package com.example.ssds.api.sourcing.dto;

import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.infra.entity.SourcingCandidate;

public record SourcingPriorityActionResponse(
        Long productId,
        SourcingStatus sourcingStatus,
        Integer timeGapDays) {

    public static SourcingPriorityActionResponse from(SourcingCandidate candidate) {
        return new SourcingPriorityActionResponse(
                candidate.getProduct().getId(),
                candidate.getProduct().getSourcingStatus(),
                candidate.getTimeGapDays());
    }
}
