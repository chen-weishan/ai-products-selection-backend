package com.example.ssds.infra.service;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.HeatTrendCalculator;
import com.example.ssds.infra.dao.TrendQueryDao;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §5.3.2／§5.3.3／§5.8 的每日合成主流程。
 *
 * <p>這是本次補實作的核心缺口：{@code TrendQueryDao} 早就有
 * {@code findCompositeHeat}／{@code findAppliedWeights}／{@code findCompositeSeries}
 * 三支查詢把 SQL 端的計算都做好了，但沒有任何呼叫端把結果組成一列
 * {@code heat_composite_daily} 寫回去——這正是「applied_weights 全部來自種子
 * SQL、看不到即時計算」的根本原因，不是單一來源權重歸零的問題而已。
 *
 * <p>本服務只負責「單一關鍵字、單一日期」的計算與落地；要不要每天跑全部關鍵字
 * 是排程任務（{@code ssds-api} 的 {@code HeatCompositeCalibrationJob}）的責任。
 *
 * <p>2026-09-22：{@link #computeAndPersist} 產生 stage 的依據已改為
 * {@link HeatTrendCalculator} 修正後的 §FR-06 規則（slope30d ±10%、加上
 * 「連續 3 週成長」訊號，見本檔案 {@code threeWeekGrowth} 的組法與
 * {@link HeatTrendCalculator} 的類別註解）。{@code volumeBelowFloor} 目前固定
 * 為 false——規格書 §5.3.2 提到這個欄位但本專案現有檔案沒有留下判定門檻，
 * 需要額外確認後才補上真正的判斷邏輯，先保留欄位但不誤植假邏輯。
 */
@Service
public class HeatCompositeCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(HeatCompositeCalibrationService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final TrendQueryDao trendQueryDao;
    private final TrendKeywordRepository trendKeywordRepository;
    private final HeatCompositeDailyRepository heatCompositeDailyRepository;

    public HeatCompositeCalibrationService(
            TrendQueryDao trendQueryDao,
            TrendKeywordRepository trendKeywordRepository,
            HeatCompositeDailyRepository heatCompositeDailyRepository) {
        this.trendQueryDao = trendQueryDao;
        this.trendKeywordRepository = trendKeywordRepository;
        this.heatCompositeDailyRepository = heatCompositeDailyRepository;
    }

    /**
     * 計算並寫入（upsert）指定關鍵字在指定日期的合成快照。
     *
     * @return 當天沒有任何可用來源的讀值時回傳 {@link Optional#empty()}
     *         （§5.7 資料不足不懲罰：不寫入、不視為熱度 0）
     */
    @Transactional
    public Optional<HeatCompositeDaily> computeAndPersist(Long keywordId, LocalDate date) {
        Double heat = trendQueryDao.findCompositeHeat(keywordId, date);
        if (heat == null) {
            log.info("關鍵字 id={} 於 {} 無任何可用來源讀值，略過（不視為熱度 0）。", keywordId, date);
            return Optional.empty();
        }

        BigDecimal heatT = BigDecimal.valueOf(heat).setScale(2, RoundingMode.HALF_UP);
        Map<String, BigDecimal> appliedWeights = trendQueryDao.findAppliedWeights(keywordId, date);

        // 2026-09-18 修正：t-7／t-30 改成「±3 天容錯窗、窗內 ≥4 天有資料才採信」，
        // 不再要求精確等於那一天（見 HeatTrendCalculator#resolveAnchor 的規則說明）。
        // 一次把三個觀測點各自容錯窗所需的區間都撈出來，避免三次查詢。
        LocalDate asOf = date.minusDays(1);
        LocalDate anchorT7Target = asOf.minusDays(7);
        LocalDate anchorT30Target = asOf.minusDays(30);
        Map<LocalDate, BigDecimal> series = trendQueryDao.findCompositeSeries(
                keywordId,
                anchorT30Target.minusDays(3),
                anchorT7Target.plusDays(3));

        BigDecimal anchorT7 = HeatTrendCalculator.resolveAnchor(series, anchorT7Target);
        BigDecimal anchorT30 = HeatTrendCalculator.resolveAnchor(series, anchorT30Target);

        BigDecimal slope7d = HeatTrendCalculator.slope(heatT, anchorT7);
        BigDecimal slope30d = HeatTrendCalculator.slope(heatT, anchorT30);

        Optional<HeatCompositeDaily> previousDay =
                heatCompositeDailyRepository.findByKeywordIdAndStatDate(keywordId, date.minusDays(1));
        HeatStage previousStage = previousDay.map(HeatCompositeDaily::getStage).orElse(null);
        short previousStageWeeks = previousDay.map(HeatCompositeDaily::getStageWeeks).orElse((short) 0);

        // §FR-06 RISING 的「連續 3 週合成熱度成長」條件：⚠️ 規格書沒留下判定式，
        // 這裡採用「當週、前 1 週、前 2 週的 slope_7d 皆 > 0」作為代理判斷
        // （見 HeatTrendCalculator 類別註解），上線前請與產品面確認。
        boolean threeWeekGrowth = slope7d != null
                && slope7d.signum() > 0
                && hasPositiveSlope7d(keywordId, date.minusDays(7))
                && hasPositiveSlope7d(keywordId, date.minusDays(14));

        HeatStage stage = HeatTrendCalculator.determineStage(slope30d, threeWeekGrowth, previousStage);
        short stageWeeks = HeatTrendCalculator.nextStageWeeks(previousStage, previousStageWeeks, stage);
        int lifespanDays = HeatTrendCalculator.estimateLifespanDays(stage, stageWeeks);
        boolean divergenceFlag = HeatTrendCalculator.detectDivergence(slope7d, slope30d);

        TrendKeyword keyword = trendKeywordRepository.getReferenceById(keywordId);

        HeatCompositeDaily row = heatCompositeDailyRepository
                .findByKeywordIdAndStatDate(keywordId, date)
                .orElseGet(() -> HeatCompositeDaily.builder().keyword(keyword).statDate(date).build());

        row.setCompositeValue(heatT);
        row.setSlope7d(slope7d);
        row.setSlope30d(slope30d);
        row.setStage(stage);
        row.setStageWeeks(stageWeeks);
        row.setEstimatedLifespanDays(lifespanDays);
        row.setAppliedWeights(writeWeightsJson(appliedWeights));
        row.setDivergenceFlag(divergenceFlag);
        // TODO(volume floor)：§5.3.2 提過這個欄位，但現有規格片段沒留下門檻數字，
        // 暫時一律 false，避免用猜的門檻誤判「量能過低」。
        row.setVolumeBelowFloor(false);

        return Optional.of(heatCompositeDailyRepository.save(row));
    }

    /** 供「連續 3 週成長」代理判斷使用：指定日期當天的 slope_7d 是否 &gt; 0。 */
    private boolean hasPositiveSlope7d(Long keywordId, LocalDate statDate) {
        return heatCompositeDailyRepository.findByKeywordIdAndStatDate(keywordId, statDate)
                .map(HeatCompositeDaily::getSlope7d)
                .filter(s -> s != null && s.signum() > 0)
                .isPresent();
    }

    private static String writeWeightsJson(Map<String, BigDecimal> weights) {
        try {
            return objectMapper.writeValueAsString(weights);
        } catch (Exception e) {
            throw new IllegalStateException("applied_weights 序列化失敗", e);
        }
    }
}