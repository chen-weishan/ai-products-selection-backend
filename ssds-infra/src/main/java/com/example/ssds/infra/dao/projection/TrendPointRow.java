package com.example.ssds.infra.dao.projection;

import java.time.LocalDate;

/** 趨勢折線單點（FR-06）。 */
public record TrendPointRow(Long keywordId, String keyword, LocalDate statDate, int heatValue) {}
