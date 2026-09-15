package com.example.ssds.api.weight.dto;

import com.example.ssds.core.domain.WeightVersionStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 權重版本詳情（規格書 §8.2 GET /weight-versions/{id}/profiles）。 */
public record WeightVersionDetailResponse(
        Long id,
        String versionNo,
        String name,
        WeightVersionStatus status,
        // 「已核准」與「現在生效中」是兩件事（§7.2.5）：status 是前者，本欄是後者。
        // 少了它，畫面上分不出四個 APPROVED 版本裡哪一個正在用。
        boolean isCurrent,
        LocalDate effectiveFrom,
        String changeNote,
        OffsetDateTime createdAt,
        OffsetDateTime approvedAt,
        List<SceneWeightGroupResponse> sceneGroups) {}
