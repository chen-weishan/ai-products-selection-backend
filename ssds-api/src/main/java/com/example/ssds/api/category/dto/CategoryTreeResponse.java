package com.example.ssds.api.category.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 品類樹節點回應結構。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryTreeResponse(
        Long id,
        String name,
        Integer leadTimeDays,
        List<CategoryTreeResponse> children
) {}
