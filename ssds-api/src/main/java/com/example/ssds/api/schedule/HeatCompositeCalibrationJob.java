package com.example.ssds.api.schedule;

import com.example.ssds.infra.dao.HeatReadingPercentileDao;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.infra.service.HeatCompositeCalibrationService;
import com.example.ssds.api.sourcing.SourcingTimeGapRecalculationJob;
import com.example.ssds.api.trend.TrendInterpretationJob;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
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
 * <p>Threads、Google Trends 與 Instagram 各自先完成採集；本任務於台北 06:00
 * 執行合成，接著依序呼叫已啟用的 B 軌時效重算與 Agent 5 enqueue。下游不再
 * 各自依賴固定分鐘差的 cron，因此只會處理本次主流程已完成的營業日資料。
 */
@Component
public class HeatCompositeCalibrationJob {

    private static final Logger log = LoggerFactory.getLogger(HeatCompositeCalibrationJob.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final HeatReadingPercentileDao percentileDao;
    private final TrendKeywordRepository trendKeywordRepository;
    private final HeatCompositeCalibrationService calibrationService;
    private final ObjectProvider<SourcingTimeGapRecalculationJob> timeGapJobProvider;
    private final ObjectProvider<TrendInterpretationJob> trendInterpretationJobProvider;

    public HeatCompositeCalibrationJob(
            HeatReadingPercentileDao percentileDao,
            TrendKeywordRepository trendKeywordRepository,
            HeatCompositeCalibrationService calibrationService,
            ObjectProvider<SourcingTimeGapRecalculationJob> timeGapJobProvider,
            ObjectProvider<TrendInterpretationJob> trendInterpretationJobProvider) {
        this.percentileDao = percentileDao;
        this.trendKeywordRepository = trendKeywordRepository;
        this.calibrationService = calibrationService;
        this.timeGapJobProvider = timeGapJobProvider;
        this.trendInterpretationJobProvider = trendInterpretationJobProvider;
    }

    @Scheduled(cron = "${ssds.calibration.heat-composite.cron:0 0 6 * * *}", zone = "Asia/Taipei")
    public void run() {
        run(LocalDate.now(TAIPEI));
    }

    void run(LocalDate businessDate) {
        int updated = percentileDao.applyPercentiles(businessDate);
        log.info("百分位重算完成：{} 筆讀值（{}）。", updated, businessDate);

        List<TrendKeyword> keywords = trendKeywordRepository.findByEnabledTrue();
        int computed = 0;
        int skipped = 0;
        for (TrendKeyword keyword : keywords) {
            try {
                boolean present = calibrationService.computeAndPersist(keyword.getId(), businessDate).isPresent();
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

        SourcingTimeGapRecalculationJob timeGapJob = timeGapJobProvider.getIfAvailable();
        if (timeGapJob != null) {
            timeGapJob.recalculateAfterDailyHeatComposition();
        }
        TrendInterpretationJob trendJob = trendInterpretationJobProvider.getIfAvailable();
        if (trendJob != null) {
            trendJob.enqueueSignificantKeywords(businessDate);
        }
    }
}
