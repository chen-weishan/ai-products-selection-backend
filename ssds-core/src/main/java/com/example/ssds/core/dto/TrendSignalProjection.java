package com.example.ssds.core.dto;

import java.math.BigDecimal;

// 斜率
public interface TrendSignalProjection {
    Long getKeywordId();
    String getKeyword();         
    BigDecimal getHeatToday();   
    BigDecimal getSlope7d();     
    BigDecimal getSlope30d();    
    String getAiSignal();        
}