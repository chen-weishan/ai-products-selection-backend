package com.example.ssds.ai.model;

import java.util.List;

public record WeightCalibrationOutput(
        String report,
        List<AdjustmentAdvice> adjustmentAdvice,
        List<String> attentionNotes) {
    public record AdjustmentAdvice(String factorCode, String explanation) {}
}
