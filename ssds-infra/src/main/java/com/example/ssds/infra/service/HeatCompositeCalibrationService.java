package com.example.ssds.infra.service;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.HeatTrendCalculator;
import com.example.ssds.core.domain.HeatValueSource;
import com.example.ssds.infra.dao.TrendQueryDao;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
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
 * <p>2026-09-22（二次修正）依規格書 v3.0.1 §FR-06／E-07／E-08 修正本流程：
 * <ul>
 *   <li>{@code stage} 判定改為只傳 {@code slope30d} 給
 *       {@link HeatTrendCalculator#determineStage(BigDecimal)}；原本組裝
 *       「連續 3 週成長」代理訊號（{@code threeWeekGrowth}）與依賴
 *       {@code previousStage} 的邏輯已移除——階段必須是當日 {@code slope_30d}
 *       的確定性函數。</li>
 *   <li>{@code stage_weeks} 改為回溯 {@code heat_composite_daily} 統計「當日
 *       往前連續相同 stage 的天數」，再交給
 *       {@link HeatTrendCalculator#stageWeeksFromContinuousDays}
 *       換算週數；不再是「前一日 stageWeeks 直接 +1」（那會把每日排程的執行
 *       次數誤當週數累加）。</li>
 *   <li>本服務作為 §FR-06 RULE 基準層，寫入時必須明確把
 *       {@code stage_source}／{@code lifespan_source} 設為 {@code RULE}，
 *       避免該列先前被 Agent 5 覆寫為 {@code AGENT} 後、這次基準重算只改了
 *       {@code stage}／{@code stageWeeks} 卻讓來源欄殘留舊值。</li>
 * </ul>
 * {@code volumeBelowFloor} 目前固定為 false——規格書 §5.3.2 提到這個欄位但本
 * 專案現有檔案沒有留下判定門檻，需要額外確認後才補上真正的判斷邏輯，先保留
 * 欄位但不誤植假邏輯。
 */
@Service
public class HeatCompositeCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(HeatCompositeCalibrationService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * {@link #countContinuousSameStageDays} 回溯查詢的天數上限。PLATEAU 的壽命
     * 查表只在 {@code stageWeeks <= 2}（即 ≤14 天）與 {@code > 2} 兩檔之間切換，
     * 90 天已遠超過任何會影響查表結果的邊界，足夠寬鬆同時避免整表掃描。
     */
    private static final long LOOKBACK_DAYS = 90;

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

        // §FR-06（v3.0.1）：stage 只依當日 slope_30d 判定，不受前一日階段影響，
        // 不再需要撈前一日的 stage／stageWeeks 來當判定輸入。
        HeatStage stage = HeatTrendCalculator.determineStage(slope30d);
        short stageWeeks = HeatTrendCalculator.stageWeeksFromContinuousDays(
                countContinuousSameStageDays(keywordId, date, stage));
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
        // §FR-06／E-07：本服務是 RULE 基準層，寫入時一律將來源標記為 RULE。
        // 若這一列先前已被 Agent 5 增益層覆寫為 AGENT，這次基準重算必須把
        // 來源改回 RULE，不可讓 stage／stageWeeks 已是新值、來源欄卻殘留 AGENT。
        row.setStageSource(HeatValueSource.RULE);
        row.setLifespanSource(HeatValueSource.RULE);

        return Optional.of(heatCompositeDailyRepository.save(row));
    }

    /**
     * §FR-06：{@code stage_weeks} 規則式算法的「回溯」步驟——自當日（含）往前
     * 逐日檢查 {@code heat_composite_daily}，統計與當日 {@code currentStage}
     * 相同的連續天數；遇到階段不同或當天缺資料（中斷）即停止，不跨越缺日繼續
     * 累計。同一天重跑會拿到相同的歷史列，因此結果具冪等性。
     *
     * <p>一次撈出 {@code LOOKBACK_DAYS} 天範圍內的列以避免逐日 N+1 查詢；若連續
     * 天數一路延伸到查詢範圍的邊界，代表實際連續天數可能更長，仍先以查到的天數
     * 計算（對應規格「無足夠歷史時以現有天數計算並於 UI 標示」）。
     */
    private long countContinuousSameStageDays(Long keywordId, LocalDate date, HeatStage currentStage) {
        List<HeatCompositeDaily> history = heatCompositeDailyRepository
                .findByKeywordIdAndStatDateBetweenOrderByStatDateDesc(
                        keywordId, date.minusDays(LOOKBACK_DAYS), date.minusDays(1));

        long continuousDays = 1; // 當日本身即算入連續天數
        LocalDate expected = date.minusDays(1);
        for (HeatCompositeDaily row : history) {
            if (!row.getStatDate().equals(expected)) {
                break; // 日期不連續（中間缺日）→ 中斷，不繼續累計
            }
            if (row.getStage() != currentStage) {
                break; // 階段不同 → 中斷
            }
            continuousDays++;
            expected = expected.minusDays(1);
        }
        return continuousDays;
    }

    private static String writeWeightsJson(Map<String, BigDecimal> weights) {
        try {
            return objectMapper.writeValueAsString(weights);
        } catch (Exception e) {
            throw new IllegalStateException("applied_weights 序列化失敗", e);
        }
    }
}