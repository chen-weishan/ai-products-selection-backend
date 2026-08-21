package com.example.ssds.api.product.dto;

import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Set;

/** FR-03-2 新增品項的請求內容。 */
public record ProductCreateRequest(
        @NotBlank(message = "品項名稱不可空白")
        @Size(max = 100, message = "品項名稱不可超過 100 字")
        String name,

        @NotNull(message = "類別不可為空")
        @Positive(message = "類別 ID 必須大於 0")
        Long categoryId,

        @Positive(message = "供應商 ID 必須大於 0")
        Long supplierId,

        @PositiveOrZero(message = "成本不可小於 0")
        @Digits(integer = 8, fraction = 2, message = "成本最多 8 位整數及 2 位小數")
        BigDecimal cost,

        @Positive(message = "建議售價必須大於 0")
        @Digits(integer = 8, fraction = 2, message = "建議售價最多 8 位整數及 2 位小數")
        BigDecimal suggestedPrice,

        @Positive(message = "最小訂購量必須大於 0")
        Integer moq,
        Season season,

        @Size(max = 200, message = "目標客群不可超過 200 字")
        String targetAudience,

        TrackType trackType,
        SourcingStatus sourcingStatus,

        @Size(max = 100, message = "物流條件不可超過 100 字")
        String logisticsCondition,

        @Positive(message = "效期天數必須大於 0")
        Integer shelfLifeDays,
        Set<@NotNull(message = "關鍵字 ID 不可為空")
                @Positive(message = "關鍵字 ID 必須大於 0") Long> keywordIds
) {
    /** 未傳關鍵字時視為空集合。 */
    public Set<Long> resolvedKeywordIds() {
        return keywordIds == null ? Set.of() : Set.copyOf(keywordIds);
    }
}
