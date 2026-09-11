package com.example.ssds.api.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 以完整清單覆蓋品項的節慶關聯度；空清單代表清除全部。 */
public record ProductFestivalAffinityUpdateRequest(
        @NotNull(message = "節慶關聯度清單不可為空")
        @Size(max = 50, message = "單一品項最多關聯 50 個節慶")
        List<@Valid ProductFestivalAffinityItemRequest> affinities
) {
}
