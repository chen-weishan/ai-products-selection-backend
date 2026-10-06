package com.example.ssds.api.calibration.dto;

import com.example.ssds.core.domain.FactorCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 校準審核（§8 {@code POST /calibration/reports/{id}/approve}，AC-15-3／AC-15-5）。
 *
 * @param action 核准／部分採納／駁回
 * @param acceptedFactors 僅 PARTIAL 使用：要接受建議的因子，套用到四榜
 * @param comment 審核說明，寫入稽核紀錄與新版本的變更說明
 */
public record ReviewCalibrationRequest(
        @NotNull(message = "請指定審核動作") Action action,
        List<FactorCode> acceptedFactors,
        @Size(max = 200) String comment) {

    public enum Action {
        APPROVE,
        PARTIAL,
        REJECT
    }
}
