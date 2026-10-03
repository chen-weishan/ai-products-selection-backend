package com.example.ssds.api.internal;

import com.example.ssds.api.schedule.HeatCompositeCalibrationJob;
import com.example.ssds.infra.dao.HeatReadingPercentileDao;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.infra.service.HeatCompositeCalibrationService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 開發測試用：手動觸發每日熱度主流程，不用等 cron 到點（06:00 Asia/Taipei）。
 * 只給本機開發測試用，正式上線前務必移除或加權限保護。
 */
@RestController
@RequestMapping("/internal/calibration")
class CalibrationDebugController {

    private static final Logger log = LoggerFactory.getLogger(CalibrationDebugController.class);

    /** 單次區間上限，避免手滑打出一整年而長時間佔用連線。 */
    private static final int MAX_RANGE_DAYS = 120;

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

    /**
     * 例：POST /internal/calibration/run-for-range/2026-08-01/2026-09-24
     *
     * <p>補算一段日期區間的每日熱度合成：對區間內每一天依序「百分位重算 → 逐一
     * 啟用關鍵字合成」，日期由舊到新（階段連續天數會回頭讀前幾天的結果，順序不能反）。
     * 只做合成，不觸發 B 軌時效重算與 Agent 5，因此不會呼叫任何 LLM。
     * 寫入的是共用資料庫的 heat_composite_daily（upsert，可重複執行）。
     */
    @PostMapping("/run-for-range/{startDate}/{endDate}")
    Map<String, Object> runForRange(@PathVariable String startDate, @PathVariable String endDate) {
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(startDate);
            end = LocalDate.parse(endDate);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式須為 yyyy-MM-dd");
        }
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate 不可早於 startDate");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "區間過長，上限 " + MAX_RANGE_DAYS + " 天");
        }

        List<TrendKeyword> keywords = trendKeywordRepository.findByEnabledTrue();
        int computed = 0;
        int skipped = 0;
        int failed = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            percentileDao.applyPercentiles(date);
            for (TrendKeyword keyword : keywords) {
                try {
                    if (calibrationService.computeAndPersist(keyword.getId(), date).isPresent()) {
                        computed++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("區間補算：關鍵字 id={} 於 {} 合成失敗，跳過。", keyword.getId(), date, e);
                }
            }
        }
        log.info("區間補算完成 {} ~ {}：{} 個關鍵字，成功 {}、無資料略過 {}、失敗 {}。",
                start, end, keywords.size(), computed, skipped, failed);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("keywords", keywords.size());
        result.put("computed", computed);
        result.put("skipped", skipped);
        result.put("failed", failed);
        return result;
    }
}