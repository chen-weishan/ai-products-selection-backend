package com.example.ssds.api.internal;

import com.example.ssds.api.schedule.HeatCompositeCalibrationJob;
import com.example.ssds.infra.dao.HeatReadingPercentileDao;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.infra.service.HeatCompositeCalibrationService;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/calibration")
class CalibrationDebugController {

    private static final Logger log = LoggerFactory.getLogger(CalibrationDebugController.class);

    private final HeatCompositeCalibrationJob job;
    private final HeatReadingPercentileDao percentileDao;
    private final TrendKeywordRepository trendKeywordRepository;
    private final HeatCompositeCalibrationService calibrationService;

    CalibrationDebugController(
            HeatCompositeCalibrationJob job,
            HeatReadingPercentileDao percentileDao,
            TrendKeywordRepository trendKeywordRepository,
            HeatCompositeCalibrationService calibrationService) {
        this.job = job;
        this.percentileDao = percentileDao;
        this.trendKeywordRepository = trendKeywordRepository;
        this.calibrationService = calibrationService;
    }

    @PostMapping("/run-now")
    void runNow() {
        job.run();
    }

    /** 例：POST /internal/calibration/run-for-date/2026-09-10 */
    @PostMapping("/run-for-date/{date}")
    void runForDate(@PathVariable String date) {
        LocalDate target = LocalDate.parse(date);

        // 補回原本漏掉的一步：沒先算百分位，composite 會因為
        // percentile_within_source IS NULL 撈不到資料而整批被略過。
        int updated = percentileDao.applyPercentiles(target);
        log.info("{} 百分位重算完成：{} 筆讀值。", target, updated);

        List<TrendKeyword> keywords = trendKeywordRepository.findByEnabledTrue();
        int computed = 0;
        int skipped = 0;
        for (TrendKeyword keyword : keywords) {
            try {
                boolean present = calibrationService.computeAndPersist(keyword.getId(), target).isPresent();
                if (present) computed++; else skipped++;
            } catch (Exception e) {
                log.warn("關鍵字 id={} 於 {} 熱度合成失敗，跳過", keyword.getId(), target, e);
            }
        }
        log.info("補算 {} 完成：{} 個關鍵字，成功 {} 筆、無資料略過 {} 筆。", target, keywords.size(), computed, skipped);
    }

    /** 例：POST /internal/calibration/run-for-range/2026-09-01/2026-09-17 */
    @PostMapping("/run-for-range/{startDate}/{endDate}")
    void runForRange(@PathVariable String startDate, @PathVariable String endDate) {
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            runForDate(date.toString());
        }
    }
}