package com.example.ssds.api.heat;

import com.example.ssds.api.heat.dto.HeatSourceDetailResponse;
import com.example.ssds.infra.entity.HeatSource;

final class HeatSourceMapper {

    private HeatSourceMapper() {}

    static HeatSourceDetailResponse toDetail(HeatSource source) {
        return new HeatSourceDetailResponse(
                source.getId(),
                source.getSourceCode().name(),
                source.getAdapterType().name(),
                source.getGranularity().name(),
                source.getCompositeWeight(),
                source.getAvailability().name(),
                source.getQuotaUsed(),
                source.getQuotaLimit(),
                source.getLastFetchedAt(),
                source.getLastProbedAt(),
                source.getConsecutiveProbeFailures(),
                source.isEnabled());
    }
}
