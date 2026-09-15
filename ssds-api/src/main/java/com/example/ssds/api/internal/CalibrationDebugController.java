package com.example.ssds.api.internal;

import com.example.ssds.api.schedule.HeatCompositeCalibrationJob;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 開發測試用：手動觸發熱度合成排程，不用等 cron 到點（04:00 Asia/Taipei）。
 * 只給本機開發測試用，正式上線前務必移除或加權限保護。
 */
@RestController
@RequestMapping("/internal/calibration")
class CalibrationDebugController {

    private final HeatCompositeCalibrationJob job;

    CalibrationDebugController(HeatCompositeCalibrationJob job) {
        this.job = job;
    }

    @PostMapping("/run-now")
    void runNow() {
        job.run();
    }
}