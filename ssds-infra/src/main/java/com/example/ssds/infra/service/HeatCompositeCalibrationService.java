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
 * {@code findCompositeHeat}／{@code findAppliedWeights}／{@code findSlopeAnchors}
 * 三支查詢把 SQL 端的計算都做好了，但沒有任何呼叫端把結果組成一列
 * {@code heat_composite_daily} 寫回去——這正是「applied_weights 全部來自種子
 * SQL、看不到即時計算」的根本原因，不是單一來源權重歸零的問題而已。
 *
 * <p>本服務只負責「單一關鍵字、單一日期」的計算與落地；要不要每天跑全部關鍵字
 * 是排程任務（{@code ssds-api} 的 {@code HeatCompositeCalibrationJob}）的責任。
 *
 * <p>⚠️ {@link #computeAndPersist} 產生的 stage／stageWeeks／estimatedLifespanDays／
 * divergenceFlag 依賴 {@link HeatTrendCalculator} 的暫定門檻，上線前請對照規格書
 * §5.3.3／§5.8 原文確認（見該類別的類別註解）。{@code volumeBelowFloor} 目前固定
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
        Map<String, BigDecimal> anchors = trendQueryDao.findSlopeAnchors(keywordId, date.minusDays(1));

        BigDecimal slope7d = HeatTrendCalculator.slope(heatT, anchors.get("t7"));
        BigDecimal slope30d = HeatTrendCalculator.slope(heatT, anchors.get("t30"));

        Optional<HeatCompositeDaily> previousDay =
                heatCompositeDailyRepository.findByKeywordIdAndStatDate(keywordId, date.minusDays(1));
        HeatStage previousStage = previousDay.map(HeatCompositeDaily::getStage).orElse(null);
        short previousStageWeeks = previousDay.map(HeatCompositeDaily::getStageWeeks).orElse((short) 0);

        HeatStage stage = HeatTrendCalculator.determineStage(slope7d, previousStage);
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

    private static String writeWeightsJson(Map<String, BigDecimal> weights) {
        try {
            return objectMapper.writeValueAsString(weights);
        } catch (Exception e) {
            throw new IllegalStateException("applied_weights 序列化失敗", e);
        }
    }
}
