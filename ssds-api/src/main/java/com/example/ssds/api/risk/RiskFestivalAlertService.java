package com.example.ssds.api.risk;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ssds.api.risk.FestivalWindowClosingDetector.Candidate;
import com.example.ssds.api.risk.FestivalWindowClosingDetector.Detection;
import com.example.ssds.api.risk.RiskAlertWriter.Outcome;
import com.example.ssds.infra.dao.FestivalAlertDao;
import com.example.ssds.infra.dao.projection.FestivalAlertRow;
import com.example.ssds.infra.repository.ProductRepository;

/**
 * §FR-10-1 {@code FESTIVAL_WINDOW_CLOSING} 批次偵測：讀取「尚未建立決策的品項 × 未到節慶」→
 * {@link FestivalWindowClosingDetector} 判定 → 經 {@link RiskAlertWriter} 寫入
 * （7 日去重、嚴重度更新、已處理者不重開都在 writer 裡）。
 *
 * <p>與季節不匹配不同，這條規則<b>隨日期變動</b>（今天不觸發、明天就可能觸發），
 * 不是評分結果的函數，所以走每日排程而不是評分後的即時檢查：由 06:35 的
 * {@code RiskSeasonFestivalAlertJob} 執行。不呼叫 LLM。
 *
 * <p>交易邊界同 {@link RiskHeatAlertService}：每一筆示警各自一個交易，單筆失敗只記錄、不中止整批。
 */
@Service
public class RiskFestivalAlertService {

    private static final Logger log = LoggerFactory.getLogger(RiskFestivalAlertService.class);

    private final FestivalAlertDao festivalAlertDao;
    private final RiskAlertRuleService ruleService;
    private final RiskAlertWriter writer;
    private final ProductRepository productRepository;
    private final TransactionTemplate transaction;
    private final FestivalWindowClosingDetector detector = new FestivalWindowClosingDetector();

    public RiskFestivalAlertService(
            FestivalAlertDao festivalAlertDao,
            RiskAlertRuleService ruleService,
            RiskAlertWriter writer,
            ProductRepository productRepository,
            PlatformTransactionManager transactionManager) {
        this.festivalAlertDao = festivalAlertDao;
        this.ruleService = ruleService;
        this.writer = writer;
        this.productRepository = productRepository;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @param candidates        納入判定的「品項 × 節慶」列數
     * @param detected          判定為即將關窗的示警數（尚未去重）
     * @param created           實際新開
     * @param refreshed         窗內已有 OPEN，更新
     * @param suppressed        窗內已有已處理者，不重開
     * @param failed            寫入失敗
     * @param skippedNoLeadTime 品類沒有前置天數而略過的品項數
     */
    public record Result(
            int candidates,
            int detected,
            int created,
            int refreshed,
            int suppressed,
            int failed,
            int skippedNoLeadTime) {

        static Result empty() {
            return new Result(0, 0, 0, 0, 0, 0, 0);
        }
    }

    public Result detect(LocalDate today, Instant detectedAt) {
        List<Candidate> candidates = festivalAlertDao.findUpcomingFestivalsWithoutDecision(today).stream()
                .map(RiskFestivalAlertService::toCandidate)
                .toList();
        if (candidates.isEmpty()) {
            log.info("{} 沒有「該節慶尚未建立決策且有未到節慶」的品項，略過檔期窗示警偵測。", today);
            return Result.empty();
        }

        // 門檻以品類為單位查一次就好，不逐品項打資料庫
        Map<Long, Integer> thresholdCache = new HashMap<>();
        Function<Long, Integer> threshold =
                id -> thresholdCache.computeIfAbsent(id, ruleService::festivalWindowClosingDays);

        FestivalWindowClosingDetector.Result detected = detector.detect(candidates, today, threshold);
        if (detected.skippedNoLeadTime() > 0) {
            log.warn("有 {} 個品項因為沒設定前置天數而略過（所屬品類沒有 category_lead_time），未判定檔期窗示警。",
                    detected.skippedNoLeadTime());
        }

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
                log.error("檔期窗示警寫入失敗：productId={}、type={}",
                        detection.productId(), detection.riskType(), exception);
            }
        }

        Result result = new Result(candidates.size(), detected.detections().size(), created, refreshed,
                suppressed, failed, detected.skippedNoLeadTime());
        log.info("檔期窗示警偵測完成（{}）：{}", today, result);
        return result;
    }

    private static Candidate toCandidate(FestivalAlertRow row) {
        return new Candidate(
                row.productId(),
                row.categoryId(),
                row.leadTimeDays(),
                row.festivalCode(),
                row.festivalName(),
                row.festivalDate(),
                row.affinity());
    }
}
