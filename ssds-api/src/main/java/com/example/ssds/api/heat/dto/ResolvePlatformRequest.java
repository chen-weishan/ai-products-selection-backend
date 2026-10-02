package com.example.ssds.api.heat.dto;

import jakarta.validation.constraints.NotBlank;

/** FR-14-1 貼上連結後、送出前預先判定平台別（AC-14-1）。前端可用來即時顯示判定結果供使用者確認。 */
public record ResolvePlatformRequest(@NotBlank(message = "來源連結不可為空") String sourceUrl) {
}
