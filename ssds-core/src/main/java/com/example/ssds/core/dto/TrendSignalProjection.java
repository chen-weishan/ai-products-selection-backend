package com.example.ssds.core.dto;

import java.math.BigDecimal;

public interface TrendSignalProjection {
    String getKeyword();         
    BigDecimal getHeatToday();   
    BigDecimal getSlope7d();     
    BigDecimal getSlope30d();    
    String getAiSignal();        
}