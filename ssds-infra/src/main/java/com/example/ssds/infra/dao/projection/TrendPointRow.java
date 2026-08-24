package com.example.ssds.infra.dao.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 趨勢折線單點（FR-06）。
 *
 * <p>資料源自 {@code heat_composite_daily}（v3.0 §7.2.3）——v1.0 的 trend_daily
 * 已廢除。合成值是 DECIMAL(6,2) 而非整數，所以這裡用 {@link BigDecimal}：
 * 取整會讓小幅變動的曲線看起來是階梯。
 */
public record TrendPointRow(Long keywordId, String keyword, LocalDate statDate,
                            BigDecimal compositeValue) {}
