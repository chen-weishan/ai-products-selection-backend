package com.example.ssds.api.heat.dto;

import com.example.ssds.core.domain.SocialPlatform;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * FR-14-1 新增人工熱度標記的請求內容。
 *
 * <p>「關聯品項／關鍵字」為必填但擇一（見 {@code manual_heat_tag} 的 CHECK 約束），
 * 因此 {@link #productId}／{@link #keywordId} 由 service 層檢查「恰好一者非空」，
 * 而非用 Bean Validation 的 group 硬湊互斥條件。
 *
 * @param sourceUrl        來源連結（必填）
 * @param platform         平台別；null 時由 {@link #sourceUrl} 自動解析（AC-14-1）
 * @param heatLevel        1–5 熱度等級
 * @param productId        關聯既有品項（A 軌或既有 B 軌）；與 keywordId 至少擇一
 * @param keywordId         關聯既有關鍵字；與 productId 至少擇一
 * @param observedAt       觀察時間；null 時預設系統當下時間
 * @param note             備註，選填
 */
public record ManualHeatTagCreateRequest(
        @NotBlank(message = "來源連結不可為空") @Size(max = 512, message = "來源連結長度不可超過 512 字") String sourceUrl,

        SocialPlatform platform,

        @NotNull(message = "熱度等級不可為空")
        @Min(value = 1, message = "熱度等級最小為 1")
        @Max(value = 5, message = "熱度等級最大為 5")
        Short heatLevel,

        @Positive(message = "品項 ID 必須大於 0") Long productId,

        @Positive(message = "關鍵字 ID 必須大於 0") Long keywordId,

        Instant observedAt,

        @Size(max = 255, message = "備註長度不可超過 255 字") String note) {
}
