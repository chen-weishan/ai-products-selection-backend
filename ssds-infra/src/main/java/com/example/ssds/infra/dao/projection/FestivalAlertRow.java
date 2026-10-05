package com.example.ssds.infra.dao.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 檔期窗示警偵測的一列：某個「尚未建立決策」的品項，與其關聯的某個<b>尚未到來</b>的節慶。
 *
 * @param leadTimeDays 品項所屬品類的前置天數；品類沒有設定時為 null（呼叫端略過，不憑空假設）
 */
public record FestivalAlertRow(
        Long productId,
        Long categoryId,
        Integer leadTimeDays,
        String festivalCode,
        String festivalName,
        LocalDate festivalDate,
        BigDecimal affinity) {
}
