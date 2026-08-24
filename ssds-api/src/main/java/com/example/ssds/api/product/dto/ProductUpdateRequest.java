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

/** FR-03-2 完整修改品項基本資料的請求內容。 */
public record ProductUpdateRequest(
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

        TrackType trackType,
        SourcingStatus sourcingStatus,

        @Size(max = 100, message = "物流條件不可超過 100 字")
        String logisticsCondition,

        @Digits(integer = 3, fraction = 1, message = "適溫下限最多 3 位整數及 1 位小數")
        BigDecimal idealTempMin,

        @Digits(integer = 3, fraction = 1, message = "適溫上限最多 3 位整數及 1 位小數")
        BigDecimal idealTempMax,

        @Positive(message = "效期天數必須大於 0")
        Integer shelfLifeDays,
        @Size(max = 5, message = "關鍵字最多選擇 5 個")
        Set<@NotNull(message = "關鍵字 ID 不可為空")
                @Positive(message = "關鍵字 ID 必須大於 0") Long> keywordIds,

        Boolean saveAsDraft
) {
    /** 未傳關鍵字時視為清空所有關聯關鍵字。 */
    public Set<Long> resolvedKeywordIds() {
        return keywordIds == null ? Set.of() : Set.copyOf(keywordIds);
    }

    /** 未指定時維持既有 API 行為：儲存完整資料。 */
    public boolean resolvedSaveAsDraft() {
        return Boolean.TRUE.equals(saveAsDraft);
    }
}
