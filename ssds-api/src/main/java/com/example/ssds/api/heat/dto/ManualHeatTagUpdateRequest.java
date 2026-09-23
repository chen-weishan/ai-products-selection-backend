package com.example.ssds.api.heat.dto;

import com.example.ssds.core.domain.SocialPlatform;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * FR-14-1 編輯既有人工熱度標記的請求內容。
 *
 * <p>不允許改變「關聯品項／關鍵字」——標記一旦建立就綁定其標的，
 * 改標的等於是把觀察對象換掉，語意上應該是「刪掉重建」而非「編輯」；
 * 也避免改動後與既有的相異標記人數統計（{@code countDistinctTaggersByProduct}
 * 等查詢）產生時間點不一致的資料。可編輯欄位僅限使用者填錯／想更新的部分。
 */
public record ManualHeatTagUpdateRequest(
        @NotBlank(message = "來源連結不可為空") @Size(max = 512, message = "來源連結長度不可超過 512 字") String sourceUrl,

        @NotNull(message = "平台別不可為空") SocialPlatform platform,

        @NotNull(message = "熱度等級不可為空")
        @Min(value = 1, message = "熱度等級最小為 1")
        @Max(value = 5, message = "熱度等級最大為 5")
        Short heatLevel,

        Instant observedAt,

        @Size(max = 255, message = "備註長度不可超過 255 字") String note) {
}
