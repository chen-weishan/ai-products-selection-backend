package com.example.ssds.api.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ssds.api.risk.RiskAlertWriter.Outcome;
import com.example.ssds.api.risk.SeasonMismatchDetector.Detection;
import com.example.ssds.api.risk.SeasonMismatchDetector.ProductClimate;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.infra.dao.SeasonAlertDao;
import com.example.ssds.infra.dao.projection.SeasonAlertRow;
import com.example.ssds.infra.repository.ProductRepository;

/**
 * §FR-10-1 {@code SEASON_MISMATCH} 批次偵測：讀取 ADOPTED／LISTED 品項最新評分的氣候百分位 →
 * {@link SeasonMismatchDetector} 判定 → 經 {@link RiskAlertWriter} 寫入
 * （7 日去重、嚴重度更新、已處理者不重開都在 writer 裡）。
 *
 * <p>不呼叫 LLM，也<b>不重新計算</b>氣候適配或百分位，只讀評分已寫入的結果。
 *
 * <p>為什麼需要批次：品項狀態在評分之後才變成 ADOPTED／LISTED（例如採納一個上次評分就偏低的品項），
 * 單靠「評分後」的即時檢查會漏掉，需要有一條路徑在狀態改變後補掃。
 *
 * <p><b>交易邊界</b>同 {@link RiskHeatAlertService}：每一筆示警各自一個交易，單筆失敗只記錄並計入
 * {@code failed}，不中止整批。
 */
@Service
public class RiskSeasonAlertService {

    private static final Logger log = LoggerFactory.getLogger(RiskSeasonAlertService.class);

    private final SeasonAlertDao seasonAlertDao;
    private final RiskAlertRuleService ruleService;
    private final RiskAlertWriter writer;
    private final ProductRepository productRepository;
    private final TransactionTemplate transaction;
    private final SeasonMismatchDetector detector = new SeasonMismatchDetector();

    public RiskSeasonAlertService(
            SeasonAlertDao seasonAlertDao,
            RiskAlertRuleService ruleService,
            RiskAlertWriter writer,
            ProductRepository productRepository,
            PlatformTransactionManager transactionManager) {
        this.seasonAlertDao = seasonAlertDao;
        this.ruleService = ruleService;
        this.writer = writer;
        this.productRepository = productRepository;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @param productsEvaluated 取得最新氣候百分位、納入判定的品項數
     * @param detected          判定為不匹配的示警數（尚未去重）
     * @param created           實際新開
     * @param refreshed         窗內已有 OPEN，更新
     * @param suppressed        窗內已有已處理者，不重開
     * @param failed            寫入失敗
     * @param skippedNoData     氣候因子無資料而略過
     */
    public record Result(
            int productsEvaluated,
            int detected,
            int created,
            int refreshed,
            int suppressed,
            int failed,
            int skippedNoData) {

        static Result empty() {
            return new Result(0, 0, 0, 0, 0, 0, 0);
        }
    }

    public Result detect(Instant detectedAt) {
        List<ProductClimate> products = seasonAlertDao.findLatestClimatePercentiles().stream()
                .map(RiskSeasonAlertService::toProductClimate)
                .toList();
        if (products.isEmpty()) {
            log.warn("沒有任何 ADOPTED／LISTED 品項具備最新氣候適配百分位，略過季節不匹配偵測。");
            return Result.empty();
        }

        // 門檻以品類為單位查一次就好，不逐品項打資料庫
        Map<Long, BigDecimal> thresholdCache = new HashMap<>();
        Function<Long, BigDecimal> threshold =
                id -> thresholdCache.computeIfAbsent(id, ruleService::seasonMismatchPercentileThreshold);

        SeasonMismatchDetector.Result detected = detector.detect(products, threshold);

        int created = 0;
        int refreshed = 0;
        int suppressed = 0;
        int failed = 0;
        for (Detection detection : detected.detections()) {
            try {
                Outcome outcome = transaction.execute(status -> writer.raise(
                        productRepository.getReferenceById(detection.productId()),
                        detection.riskType(),
                        detection.severity(),
                        detection.triggerValue(),
                        detectedAt));
                if (outcome == Outcome.CREATED) {
                    created++;
                } else if (outcome == Outcome.REFRESHED) {
                    refreshed++;
                } else {
                    suppressed++;
                }
            } catch (RuntimeException exception) {
                failed++;
                log.error("季節不匹配示警寫入失敗：productId={}、type={}",
                        detection.productId(), detection.riskType(), exception);
            }
        }

        Result result = new Result(products.size(), detected.detections().size(), created, refreshed,
                suppressed, failed, detected.skippedNoData());
        log.info("季節不匹配偵測完成：{}", result);
        return result;
    }

    private static ProductClimate toProductClimate(SeasonAlertRow row) {
        return new ProductClimate(
                row.productId(),
                row.categoryId(),
                ProductStatus.valueOf(row.status()),
                row.percentile(),
                row.dataAvailable());
    }
}
