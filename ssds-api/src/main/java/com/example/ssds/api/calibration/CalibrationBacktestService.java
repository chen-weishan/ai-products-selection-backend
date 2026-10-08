package com.example.ssds.api.calibration;

import com.example.ssds.api.admin.OperationalRuntimeConfigurable;
import com.example.ssds.api.admin.RuntimeSettingsService.OperationalConfig;
import com.example.ssds.api.calibration.dto.BacktestRequest;
import com.example.ssds.api.calibration.dto.BacktestResponse;
import com.example.ssds.calibration.Backtester;
import com.example.ssds.calibration.CalibrationSample;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AC-15-4：任意兩個權重版本在同一批歷史資料上的表現比較（§8 {@code POST /calibration/backtest}）。
 *
 * <p>版本不限狀態：草稿也能回測，校準產生的草稿可在 FR-08 核准生效前先驗證。
 * 樣本為請求當下以前已回填的全部決策。
 */
@Service
public class CalibrationBacktestService implements OperationalRuntimeConfigurable {

    private final CalibrationDataLoader loader;
    private volatile int minSample;
    private final Clock clock;

    @Autowired
    public CalibrationBacktestService(
            CalibrationDataLoader loader, @Value("${ssds.calibration.min-sample:200}") int minSample) {
        this(loader, minSample, Clock.systemUTC());
    }

    CalibrationBacktestService(CalibrationDataLoader loader, int minSample, Clock clock) {
        this.loader = loader;
        this.minSample = minSample;
        this.clock = clock;
    }

    @Override
    public void reconfigure(OperationalConfig config) {
        minSample = config.calibrationMinSample();
    }

    @Transactional(readOnly = true)
    public BacktestResponse compare(BacktestRequest request) {
        Instant cutoff = clock.instant();
        List<CalibrationSample> samples = loader.samples(cutoff);
        List<Backtester.Outcome> results = List.of(
                Backtester.run(samples, loader.schemeOf("VERSION", loader.version(request.versionAId()))),
                Backtester.run(samples, loader.schemeOf("VERSION", loader.version(request.versionBId()))));
        boolean below = samples.size() < minSample;
        return new BacktestResponse(cutoff, samples.size(), minSample, below,
                below ? CalibrationReportService.VALIDITY_WARNING.formatted(minSample) : null,
                results);
    }
}
