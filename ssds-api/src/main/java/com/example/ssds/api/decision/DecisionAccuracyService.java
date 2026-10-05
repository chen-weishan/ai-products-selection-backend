package com.example.ssds.api.decision;

import com.example.ssds.api.decision.DecisionQueryService.DecisionSearchCriteria;
import com.example.ssds.api.decision.dto.DecisionAccuracyResponse;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SelloutStatus;
import com.example.ssds.infra.entity.DecisionRecord;
import com.example.ssds.infra.repository.DecisionRecordRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 預測準確度分析（規格書 §FR-11-3、AC-11-4、AC-11-5）。
 *
 * <p>母體為篩選範圍內、品項未刪除的全部決策；各指標再各自取子集合：
 * <ul>
 * <li>相關係數、A 級達標率：已回填者（決策綁定的一律是主情境評分，AC-11-1）</li>
 * <li>情境覆寫率：全部決策的快照</li>
 * <li>AI 採納率：全部決策中 followed_ai = true 的比例（§FR-11-3 字面定義；無 AI 建議的決策 followed_ai 為 false，計入分母）</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class DecisionAccuracyService {

    /** §FR-11-3 必要揭露的固定文案，畫面照抄，不可關閉（AC-11-5）。 */
    static final String VALIDITY_WARNING = "樣本數不足，本數據僅供觀察趨勢，不足以支持權重調整";

    private static final Set<SelloutStatus> HIT = Set.of(SelloutStatus.EARLY_SELLOUT, SelloutStatus.ON_TIME);
    private static final int RATE_SCALE = 4;

    private final DecisionRecordRepository decisionRecordRepository;
    private final int minSample;

    public DecisionAccuracyService(
            DecisionRecordRepository decisionRecordRepository,
            @Value("${ssds.calibration.min-sample:200}") int minSample) {
        this.decisionRecordRepository = decisionRecordRepository;
        this.minSample = minSample;
    }

    public DecisionAccuracyResponse analyze(LocalDate from, LocalDate to, Long categoryId, Long decidedBy) {
        DecisionSearchCriteria criteria = new DecisionSearchCriteria(
                from, to, decidedBy, categoryId, null, null, null);
        List<DecisionRecord> population = decisionRecordRepository
                .findAll(DecisionQueryService.toSpecification(criteria));
        return compute(population, from, to, categoryId, decidedBy, minSample);
    }

    static DecisionAccuracyResponse compute(
            List<DecisionRecord> population, LocalDate from, LocalDate to,
            Long categoryId, Long decidedBy, int minSample) {
        List<DecisionRecord> filled = population.stream().filter(d -> d.getResult() != null).toList();

        double[] scores = filled.stream().mapToDouble(d -> d.getScore().getFinalScore().doubleValue()).toArray();
        double[] sales = filled.stream().mapToDouble(d -> d.getResult().getActualQty()).toArray();

        List<DecisionRecord> gradeA = filled.stream().filter(d -> d.getScore().getGrade() == Grade.A).toList();
        long gradeAHit = gradeA.stream().filter(d -> HIT.contains(d.getResult().getSelloutStatus())).count();

        long overridden = population.stream()
                .filter(d -> d.getSnapshot() != null && d.getSnapshot().isSceneOverridden())
                .count();

        long followed = population.stream().filter(DecisionRecord::isFollowedAi).count();

        boolean belowMinSample = filled.size() < minSample;
        return new DecisionAccuracyResponse(
                from, to, categoryId, decidedBy,
                population.size(),
                filled.size(),
                pearson(scores, sales),
                rate(gradeAHit, gradeA.size()), gradeAHit, gradeA.size(),
                rate(overridden, population.size()), overridden,
                rate(followed, population.size()), followed,
                minSample,
                belowMinSample,
                belowMinSample ? VALIDITY_WARNING : null);
    }

    /**
     * 皮爾森積差相關係數。樣本少於 2 筆或任一變數無變異（分母為 0）時無定義，回 null。
     */
    static BigDecimal pearson(double[] x, double[] y) {
        int n = x.length;
        if (n < 2) {
            return null;
        }
        double meanX = 0;
        double meanY = 0;
        for (int i = 0; i < n; i++) {
            meanX += x[i];
            meanY += y[i];
        }
        meanX /= n;
        meanY /= n;
        double cov = 0;
        double varX = 0;
        double varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        if (varX == 0 || varY == 0) {
            return null;
        }
        return BigDecimal.valueOf(cov / Math.sqrt(varX * varY)).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }
}
