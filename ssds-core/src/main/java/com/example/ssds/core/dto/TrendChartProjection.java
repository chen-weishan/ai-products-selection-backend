package com.example.ssds.core.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

// 趨勢歷史折線圖專用

public interface TrendChartProjection {
    LocalDate getDate();         
    BigDecimal getHeatScore();   
}