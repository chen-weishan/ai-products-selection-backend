package com.example.ssds.api.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ssds.api.risk.HeatAlertDetector.Detection;
import com.example.ssds.api.risk.HeatAlertDetector.ProductHeat;
import com.example.ssds.api.risk.RiskAlertWriter.Outcome;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.infra.dao.HeatAlertDao;
import com.example.ssds.infra.dao.projection.HeatAlertRow;
import com.example.ssds.infra.repository.ProductRepository;

/**
 * §FR-10-2 每日熱度異常偵測：讀取偵測日的熱度斜率 → {@link HeatAlertDetector} 判定 →
 * 經 {@link RiskAlertWriter} 寫入（去重、嚴重度更新、已處理者不重開都在 writer 裡）。
 *
 * <p>不呼叫 LLM。
 *
 * <p><b>交易邊界</b>：本類別自己不開大交易。每一筆示警各自一個交易，單筆失敗只記錄並計入
 * {@code failed}，不會中止整批——否則一個品項的資料問題會讓當天所有品項都沒有示警。
 *
 * <p>偵測日沒有任何合成資料（例如 06:00 合成失敗）時<b>不退回前一天的資料</b>：
 * 拿昨天的斜率開今天的示警，等於把已經過時的判斷當成新的。回傳空結果並記警告。
 */
@Service
public class RiskHeatAlertService {

    private static final Logger log = LoggerFactory.getLogger(RiskHeatAlertService.class);

    private final HeatAlertDao heatAlertDao;
    private final RiskAlertRuleService ruleService;
    private final RiskAlertWriter writer;
    private final ProductRepository productRepository;
    private final TransactionTemplate transaction;
    private final HeatAlertDetector detector = new HeatAlertDetector();

    public RiskHeatAlertService(
            HeatAlertDao heatAlertDao,
            RiskAlertRuleService ruleService,
            RiskAlertWriter writer,
            ProductRepository productRepository,
            PlatformTransactionManager transactionManager) {
        this.heatAlertDao = heatAlertDao;
        this.ruleService = ruleService;
        this.writer = writer;
        this.productRepository = productRepository;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @param productsEvaluated         有偵測日斜率、納入判定的品項數
     * @param detected                  判定為異常的示警數（尚未去重）
     * @param created                   實際新開
     * @param refreshed                 窗內已有 OPEN，更新
     * @param suppressed                窗內已有已處理者，不重開
     * @param failed                    寫入失敗
     * @param skippedInsufficientSample HEAT_SURGE 因同品類樣本不足略過的品項數
     * @param skippedVolumeFloor        HEAT_SURGE 因熱度量級不足略過的品項數
     */
    public record Result(
            int productsEvaluated,
            int detected,
            int created,
            int refreshed,
            int suppressed,
            int failed,
            int skippedInsufficientSample,
            int skippedVolumeFloor) {

        static Result empty() {
            return new Result(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public Result detect(LocalDate statDate, Instant detectedAt) {
        List<ProductHeat> products = effectiveHeat(heatAlertDao.findProductHeat(statDate));
        if (products.isEmpty()) {
            log.warn("{} 沒有任何可用的熱度合成資料（slope_7d），略過熱度示警偵測。", statDate);
            return Result.empty();
        }

        // 門檻以品類為單位查一次就好，不逐品項打資料庫
        Map<Long, BigDecimal> crashCache = new HashMap<>();
        Map<Long, BigDecimal> surgeCache = new HashMap<>();
        Function<Long, BigDecimal> crash =
                id -> crashCache.computeIfAbsent(id, ruleService::heatCrashSlopeThreshold);
        Function<Long, BigDecimal> surge =
                id -> surgeCache.computeIfAbsent(id, ruleService::heatSurgePercentile);

        HeatAlertDetector.Result detected = detector.detect(products, crash, surge);

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
                log.error("熱度示警寫入失敗：productId={}、type={}",
                        detection.productId(), detection.riskType(), exception);
            }
        }

        Result result = new Result(products.size(), detected.detections().size(), created, refreshed,
                suppressed, failed, detected.skippedInsufficientSample(), detected.skippedVolumeFloor());
        log.info("熱度示警偵測完成（{}）：{}", statDate, result);
        return result;
    }

    /**
     * 每品項一筆：DAO 已依 slope_7d 由大到小排序，品項的第一列就是生效關鍵字
     * （§5.3.3 多關鍵字取最高者）。
     */
    private static List<ProductHeat> effectiveHeat(List<HeatAlertRow> rows) {
        List<ProductHeat> products = new ArrayList<>();
        Long lastProductId = null;
        for (HeatAlertRow row : rows) {
            if (row.productId().equals(lastProductId)) {
                continue;
            }
            lastProductId = row.productId();
            products.add(new ProductHeat(
                    row.productId(),
                    row.categoryId(),
                    row.parentCategoryId(),
                    ProductStatus.valueOf(row.status()),
                    row.keywordId(),
                    row.keyword(),
                    row.slope7d(),
                    row.volumeBelowFloor()));
        }
        return products;
    }
}
