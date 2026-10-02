package com.example.ssds.api.heat.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/**
 * S-16 熱度來源操作（規格書 FR-14-2）：啟用／停用、調整合成權重。
 * 兩欄皆為選填、null 表不異動——同一個 PUT 端點同時服務「只想切換啟用狀態」
 * 與「只想調整權重」兩種畫面操作，不必為此拆兩支 API。
 *
 * <p>僅 SYS_ADMIN 可呼叫（AC-14-5），由 controller 的 {@code @PreAuthorize} 把關。
 */
public record HeatSourceUpdateRequest(
        Boolean enabled,

        @DecimalMin(value = "0.000", message = "合成權重不可小於 0")
        @DecimalMax(value = "1.000", message = "合成權重不可大於 1")
        @Digits(integer = 1, fraction = 3, message = "合成權重最多 1 位整數及 3 位小數")
        BigDecimal compositeWeight) {
}
