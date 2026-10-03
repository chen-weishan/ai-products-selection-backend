package com.example.ssds.api.calibration.dto;

import com.example.ssds.calibration.Backtester;
import java.time.Instant;
import java.util.List;

/**
 * @param cutoff 樣本截止點（請求當下）：此刻以前已回填的全部決策
 * @param results 依請求順序：版本 A、版本 B
 */
public record BacktestResponse(
        Instant cutoff,
        int sampleSize,
        int minSample,
        boolean belowMinSample,
        String validityWarning,
        List<Backtester.Outcome> results) {
}
