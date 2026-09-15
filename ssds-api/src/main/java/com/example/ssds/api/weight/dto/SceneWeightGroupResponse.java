package com.example.ssds.api.weight.dto;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 一個榜（情境）的完整規則：六個加分因子的權重 + 該榜的 A／B 分級門檻。
 *
 * <p>{@code weightSum} 是伺服器算好給前端顯示的，讓 S-09 畫面不必自己加總；
 * AC-08-1 的把關在存檔時做，不靠這個欄位。
 */
public record SceneWeightGroupResponse(
        SceneType sceneType,
        Map<FactorCode, BigDecimal> weights,
        BigDecimal weightSum,
        BigDecimal gradeAMin,
        BigDecimal gradeBMin) {}
