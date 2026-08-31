package com.example.ssds.api.calibration.dto;

import com.example.ssds.ai.model.*;
import com.example.ssds.infra.entity.CalibrationReport;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.List;

public record WeightCalibrationResponse(
        Long reportId,String quarter,int sampleSize,boolean statisticallyValid,
        JsonNode regressionResult,JsonNode backtestResult,String report,
        List<WeightCalibrationOutput.AdjustmentAdvice> adjustmentAdvice,List<String> attentionNotes,
        boolean fallbackApplied,FallbackReason fallbackReason,boolean cacheHit,
        String model,String modelAlias,String promptVersion,int requestCount,OffsetDateTime interpretedAt) {
    private static final ZoneId ZONE=ZoneId.of("Asia/Taipei");
    public static WeightCalibrationResponse from(CalibrationReport entity,JsonNode regression,JsonNode backtest,
            WeightCalibrationResult result,Instant at) {
        return new WeightCalibrationResponse(entity.getId(),entity.getQuarter(),entity.getSampleSize(),entity.isStatisticallyValid(),
                regression,backtest,result.output().report(),result.output().adjustmentAdvice(),result.output().attentionNotes(),
                result.fallbackApplied(),result.fallbackReason(),result.cacheHit(),result.model(),"MODEL_REASONING",
                result.promptVersion(),result.requestCount(),at.atZone(ZONE).toOffsetDateTime());
    }
}
