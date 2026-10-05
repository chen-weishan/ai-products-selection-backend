package com.example.ssds.api.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationResult;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;

/** 評分完成後依結果或快照中的扣分／信心／氣候值開立即時示警。 */
@Service
public class RiskAlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RiskAlertEvaluator.class);
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final ProductScoreRepository scores;
    private final ProductRepository products;
    private final RiskAlertWriter writer;
    private final RiskAlertRuleService rules;
    private final SeasonMismatchDetector seasonDetector = new SeasonMismatchDetector();

    public RiskAlertEvaluator(
            ProductScoreRepository scores,
            ProductRepository products,
            RiskAlertWriter writer,
            RiskAlertRuleService rules) {
        this.scores = scores;
        this.products = products;
        this.writer = writer;
        this.rules = rules;
    }

    /** 示警是輔助工作，任何寫入失敗都不能回滾已成功的評分。 */
    public void evaluate(Long productId, EvaluationResult result, Instant detectedAt) {
        if (result == null) {
            return;
        }
        if (result.status() == LastScoringStatus.INSUFFICIENT_DATA) {
            try {
                Product product = products.getReferenceById(productId);
                String trigger = result.message() == null || result.message().isBlank()
                        ? "評分資料不足，無法產生分數"
                        : "評分資料不足：" + result.message();
                safeRaise(product, RiskTypes.DATA_INSUFFICIENT, Severity.MEDIUM, trigger, detectedAt);
            } catch (RuntimeException exception) {
                log.error("資料不足示警評估失敗：productId={}", productId, exception);
            }
            return;
        }
        if (result.status() != LastScoringStatus.SCORED || result.primaryScoreId() == null) return;
        try {
            ProductScore score = scores.findWithFactorsById(result.primaryScoreId()).orElseThrow();
            Product product = score.getProduct();
            Map<FactorCode, ScoreFactor> factors = new EnumMap<>(FactorCode.class);
            score.getFactors().forEach(factor -> factors.put(factor.getFactorCode(), factor));
            raisePenalty(product, factors, FactorCode.REVIEW_RISK, RiskTypes.REVIEW_RISK,
                    Severity.MEDIUM, detectedAt);
            raisePenalty(product, factors, FactorCode.LOGISTICS_RISK, RiskTypes.LOGISTICS_RISK,
                    Severity.MEDIUM, detectedAt);
            raisePenalty(product, factors, FactorCode.INVENTORY_RISK, RiskTypes.INVENTORY_RISK,
                    Severity.MEDIUM, detectedAt);

            BigDecimal penaltyThreshold = RiskAlertRuleService.DEFAULT_PENALTY_CAP_THRESHOLD;
            if (score.getPenaltySubtotal().compareTo(penaltyThreshold) >= 0) {
                safeRaise(product, RiskTypes.PENALTY_CAP, Severity.HIGH,
                        "扣分小計 " + score.getPenaltySubtotal().toPlainString() + "（門檻 "
                                + penaltyThreshold.toPlainString() + "）", detectedAt);
            }
            int confidenceThreshold = rules.lowConfidenceThreshold(product.getCategory().getId());
            if (score.getConfidence() < confidenceThreshold) {
                safeRaise(product, RiskTypes.LOW_CONFIDENCE, Severity.LOW,
                        "信心度 " + score.getConfidence() + "（門檻 " + confidenceThreshold + "）", detectedAt);
            }
            ScoreFactor climate = factors.get(FactorCode.CLIMATE);
            if (climate != null) {
                SeasonMismatchDetector.Result mismatch = seasonDetector.detect(
                        java.util.List.of(new SeasonMismatchDetector.ProductClimate(
                                productId,
                                product.getCategory().getId(),
                                product.getStatus(),
                                climate.getNormalizedValue(),
                                climate.isDataAvailable())),
                        category -> rules.seasonMismatchPercentileThreshold(category));
                for (SeasonMismatchDetector.Detection detection : mismatch.detections()) {
                    safeRaise(product, detection.riskType(), detection.severity(),
                            detection.triggerValue(), detectedAt);
                }
            }
        } catch (RuntimeException exception) {
            log.error("評分完成但風險示警評估失敗：productId={}", productId, exception);
        }
    }

    private void raisePenalty(
            Product product,
            Map<FactorCode, ScoreFactor> factors,
            FactorCode factorCode,
            String riskType,
            Severity severity,
            Instant detectedAt) {
        ScoreFactor factor = factors.get(factorCode);
        if (factor == null || !factor.isDataAvailable() || factor.getPenaltyValue() == null
                || factor.getPenaltyValue().compareTo(ZERO) <= 0) {
            return;
        }
        Severity effectiveSeverity = factorCode == FactorCode.REVIEW_RISK
                && factor.getPenaltyValue().compareTo(BigDecimal.valueOf(20)) >= 0
                        ? Severity.HIGH : severity;
        safeRaise(product, riskType, effectiveSeverity,
                factor.getNote() == null ? "扣分 " + factor.getPenaltyValue().toPlainString() : factor.getNote(),
                detectedAt);
    }

    private void safeRaise(Product product, String riskType, Severity severity, String trigger, Instant detectedAt) {
        try {
            writer.raise(product, riskType, severity, trigger, detectedAt);
        } catch (RuntimeException exception) {
            log.error("示警寫入失敗：productId={}、type={}", product.getId(), riskType, exception);
        }
    }
}
