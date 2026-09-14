package com.example.ssds.api.schedule;

import com.example.ssds.infra.dao.HeatReadingPercentileDao;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.infra.service.HeatCompositeCalibrationService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日把「各來源當日讀值」合成為「每關鍵字每日的一列 heat_composite_daily」
 * （§5.3.2／§5.3.3／§5.8）。
 *
 * <p>這支任務是先前完全缺漏的一塊：{@code TrendQueryDao} 的合成/權重 SQL、
 * 以及 {@link HeatReadingPercentileDao} 的百分位批次都已經存在，但沒有任何排程
 * 把兩者串起來寫回資料庫——現有資料庫裡看到的 applied_weights 都是種子 SQL
 * 寫死的，跟 IG 來源狀態是 AVAILABLE 還是 DEGRADED 完全無關。
 *
 * <p>執行順序必須是：①先重算當天所有來源的 percentile_within_source，
 * ②再逐一關鍵字合成——順序反了的話，合成會讀到「今天」的百分位是舊值或 NULL。
 *
 * <p>時間點暫訂台北 04:00（晚於 Instagram 每週一 03:30 的採集），
 * 但 Threads／Google Trends 目前都還沒有實際的 ingest 排程
 * （{@code ssds-ingest} 只有 Instagram 一個 adapter），所以這支任務目前主要是
 * 補上「合成計算」這一層——真正每天有新讀值可合成，還要等其餘來源的 ingest
 * 一併補齊。cron 之後應該改成「晚於全部來源當天 ingest 完成」的時間，
 * 目前先用一個合理預設。
 */
@Component
public class HeatCompositeCalibrationJob {

    private static final Logger log = LoggerFactory.getLogger(HeatCompositeCalibrationJob.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final HeatReadingPercentileDao percentileDao;
    private final TrendKeywordRepository trendKeywordRepository;
    private final HeatCompositeCalibrationService calibrationService;

    public HeatCompositeCalibrationJob(
            HeatReadingPercentileDao percentileDao,
            TrendKeywordRepository trendKeywordRepository,
            HeatCompositeCalibrationService calibrationService) {
        this.percentileDao = percentileDao;
        this.trendKeywordRepository = trendKeywordRepository;
        this.calibrationService = calibrationService;
    }

    @Scheduled(cron = "${ssds.calibration.heat-composite.cron:0 0 4 * * *}", zone = "Asia/Taipei")
    public void run() {
        LocalDate today = LocalDate.now(TAIPEI);

        int updated = percentileDao.applyPercentiles(today);
        log.info("百分位重算完成：{} 筆讀值（{}）。", updated, today);

        List<TrendKeyword> keywords = trendKeywordRepository.findByEnabledTrue();
        int computed = 0;
        int skipped = 0;
        for (TrendKeyword keyword : keywords) {
            try {
                boolean present = calibrationService.computeAndPersist(keyword.getId(), today).isPresent();
                if (present) {
                    computed++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                // 單一關鍵字算失敗不中斷整批，比照 InstagramHeatIngestJob 的既有慣例。
                log.warn("關鍵字 id={} 熱度合成失敗，跳過：{}", keyword.getId(), keyword.getKeyword(), e);
            }
        }
        log.info("熱度合成完成：{} 個關鍵字，成功 {} 筆、無資料略過 {} 筆。", keywords.size(), computed, skipped);
    }
}
