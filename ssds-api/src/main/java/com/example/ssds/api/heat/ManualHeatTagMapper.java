package com.example.ssds.api.heat;

import com.example.ssds.api.heat.dto.ManualHeatTagResponse;
import com.example.ssds.infra.entity.ManualHeatTag;
import java.time.Instant;

final class ManualHeatTagMapper {

    private ManualHeatTagMapper() {}

    /**
     * AC-14-2「畫面顯示現行權重」：直接沿用 entity 既有的
     * {@link ManualHeatTag#decayFactor(Instant)}（14 天 0.5、30 天 0），
     * 以「現在」為評估點——與排程算 raw_value 時用的評估點不同（排程用當天
     * 批次執行時間），純粹是給使用者看「這筆標記現在還值多少權重」的即時展示。
     */
    static ManualHeatTagResponse toResponse(ManualHeatTag tag) {
        double currentWeight = tag.decayFactor(Instant.now());
        return new ManualHeatTagResponse(
                tag.getId(),
                tag.getSourceUrl(),
                tag.getPlatform() != null ? tag.getPlatform().name() : null,
                tag.getHeatLevel(),
                tag.getProduct() != null ? tag.getProduct().getId() : null,
                tag.getProduct() != null ? tag.getProduct().getName() : null,
                tag.getKeyword() != null ? tag.getKeyword().getId() : null,
                tag.getKeyword() != null ? tag.getKeyword().getKeyword() : null,
                tag.getObservedAt(),
                tag.getTaggedBy() != null ? tag.getTaggedBy().getId() : null,
                tag.getTaggedBy() != null ? tag.getTaggedBy().getDisplayName() : null,
                tag.getNote(),
                currentWeight);
    }
}
