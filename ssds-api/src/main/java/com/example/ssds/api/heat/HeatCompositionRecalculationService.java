package com.example.ssds.api.heat;

import com.example.ssds.api.scoring.PureScoringBatchService;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.infra.service.HeatCompositeCalibrationService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AC-14-5：合成權重變動後的「重算合成 → 全量重評分」。
 *
 * <p>順序不可對調：評分的趨勢因子讀 {@code heat_composite_daily}，
 * 若先評分再重算合成，評分會吃到舊權重算出的合成值。
 *
 * <p>只重算「當日」的合成列。權重不影響各來源的同來源百分位
 * （{@code percentile_within_source}），因此不需重跑百分位；
 * 歷史日期的列保留當時實際採用的權重，不回頭改寫。
 * 當日尚無任何來源讀值的關鍵字會被略過（§5.7 資料不足不懲罰，不視為熱度 0）。
 *
 * <p>單一關鍵字失敗不中斷整批，與 {@code HeatCompositeCalibrationJob} 的慣例一致；
 * 評分批次本身也是逐品項獨立提交。
 */
@Service
public class HeatCompositionRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(HeatCompositionRecalculationService.class);

    private final TrendKeywordRepository trendKeywordRepository;
    private final HeatCompositeCalibrationService calibrationService;
    private final PureScoringBatchService scoringBatchService;

    public HeatCompositionRecalculationService(
            TrendKeywordRepository trendKeywordRepository,
            HeatCompositeCalibrationService calibrationService,
            PureScoringBatchService scoringBatchService) {
        this.trendKeywordRepository = trendKeywordRepository;
        this.calibrationService = calibrationService;
        this.scoringBatchService = scoringBatchService;
    }

    public Result recomposeAndRescore(LocalDate businessDate, Instant attemptedAt) {
        List<TrendKeyword> keywords = trendKeywordRepository.findByEnabledTrue();
        int composed = 0;
        int skipped = 0;
        int failed = 0;
        for (TrendKeyword keyword : keywords) {
            try {
                if (calibrationService.computeAndPersist(keyword.getId(), businessDate).isPresent()) {
                    composed++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("關鍵字 id={} 權重變動後重算合成失敗，跳過：{}", keyword.getId(), keyword.getKeyword(), e);
            }
        }

        PureScoringBatchService.BatchResult batch = scoringBatchService.evaluateAll(attemptedAt);
        return new Result(
                composed, skipped, failed,
                batch.attemptedCount(), batch.scoredCount(), batch.insufficientCount(), batch.failedCount());
    }

    public record Result(
            int keywordsComposed,
            int keywordsSkipped,
            int keywordsFailed,
            int productsAttempted,
            int productsScored,
            int productsInsufficient,
            int productsFailed) {}
}
