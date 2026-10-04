package com.example.ssds.api.score.dto;

import java.math.BigDecimal;

import com.example.ssds.core.domain.FactorCode;

/**
 * 排行列上的一根因子長條（規格書 §FR-04「因子組成」）。
 *
 * <p>
 * {@code dataAvailable = false} 時 UI 顯示灰底空條，且該因子不扣分（§5.7）。
 * 此時 {@code normalizedValue} 一定是 null，但 <b>{@code weight} 不一定</b>：
 * 可能是 null，也可能是 0。判斷「這個因子有沒有資料」<b>只能看 {@code dataAvailable}</b>，
 * 寫成 {@code weight == null} 會漏判。
 *
 * <p>
 * 扣分因子的 {@code normalizedValue} 與 {@code weight} 恆為 null
 * （§5.2.2 扣分固定生效、不參與權重，也不做百分位換算），前端不能當 0 畫。
 *
 * <p>
 * {@code driving*} 四欄：多對一取最大值時實際生效的標的（v3.0.1 E-01／E-02）。
 * 只有 {@code TREND} 會有生效關鍵字（§5.3.3）、只有 {@code FESTIVAL} 會有生效節慶
 * （AC-17-6「於 UI 標示生效節慶」）；其餘因子、或評分引擎未寫入時皆為 null。
 */
public record ScoreFactorBarResponse(
        FactorCode factorCode,
        BigDecimal normalizedValue,
        BigDecimal weight,
        boolean dataAvailable,
        boolean imputed,
        Long drivingKeywordId,
        String drivingKeyword,
        Long drivingFestivalId,
        String drivingFestivalName) {
}