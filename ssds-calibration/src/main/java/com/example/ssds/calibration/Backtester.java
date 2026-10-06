package com.example.ssds.calibration;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Grade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;

/**
 * 歷史回測（§FR-15「回測驗證」、AC-15-4）：用同一批已回填樣本，以指定權重規則重算分數，
 * 比較預測表現。
 *
 * <p>重算沿用 §5.5／§5.6：加分小計 = Σ w_i·normalized_i（缺值因子權重按比例分攤，§5.7），
 * 選品分數 = max(0, 加分 − 扣分)，依該榜門檻分級，扣分 ≥ 20 者最高只給 B。
 * 扣分沿用原評分，不隨權重變動（§5.2.2）。
 *
 * <p>與評分引擎的已知差異：引擎把缺值分攤後的權重先捨入到三位小數再存入 {@code score_factor.weight}
 * （加總可能為 1.001），本類別用精確分攤。以現行版本重算有缺值因子的品項時，分數可能差 0.0x 分
 * （2026-10 dev 資料 8 筆實測最大差 0.03，排序不變）；只有分數恰好落在門檻邊緣時才會改變分級。
 * 回測比較的是同一套算法下不同權重的表現，各方案一致採精確分攤。
 *
 * <p>兩個指標：
 * <ul>
 * <li>相關係數：重算分數與實際銷量的 Spearman 等級相關（理由見 {@link RankCorrelation}）。
 *     另附 Pearson 供與 S-12 對照；但 S-12 用的是評分當時存下的分數，回測是以指定版本重算，
 *     只有樣本都由該版本評分時兩者才會一致（差異上限即上段的 0.0x 分捨入）</li>
 * <li>A 級達標率：重算後為 A 級者中，售罄狀況為 EARLY_SELLOUT／ON_TIME 的比例（§FR-11-3 定義）</li>
 * </ul>
 */
public final class Backtester {

    private Backtester() {
    }

    public static Outcome run(List<CalibrationSample> samples, WeightScheme scheme) {
        double[] scores = new double[samples.size()];
        double[] sales = new double[samples.size()];
        int gradeA = 0;
        int gradeAHit = 0;
        for (int i = 0; i < samples.size(); i++) {
            CalibrationSample sample = samples.get(i);
            Map<FactorCode, Double> weights = scheme.weights().get(sample.sceneType());
            WeightScheme.GradeCut cut = scheme.thresholds().get(sample.sceneType());
            if (weights == null || cut == null) {
                throw new IllegalArgumentException(
                        scheme.label() + " 缺少 " + sample.sceneType() + " 榜的權重或門檻");
            }
            double score = finalScore(sample, weights);
            scores[i] = score;
            sales[i] = sample.actualQty();
            if (grade(score, sample.penaltySubtotal(), cut) == Grade.A) {
                gradeA++;
                if (sample.hit()) {
                    gradeAHit++;
                }
            }
        }
        RankCorrelation.Result correlation = RankCorrelation.of(scores, sales);
        return new Outcome(scheme.code(), scheme.label(), scheme.versionId(), samples.size(),
                correlation == null ? null : correlation.correlation(),
                pearson(scores, sales),
                gradeA, gradeAHit,
                gradeA == 0 ? null : (double) gradeAHit / gradeA);
    }

    /**
     * 皮爾森相關，定義與 S-12 準確度（{@code DecisionAccuracyService#pearson}）相同：
     * 少於 2 筆或任一變數無變異時為 null。
     */
    static Double pearson(double[] x, double[] y) {
        if (x.length < 2) {
            return null;
        }
        double r = new PearsonsCorrelation().correlation(x, y);
        return Double.isNaN(r) ? null : r;
    }

    /** 選品分數，四捨五入到小數兩位（與 {@code product_score.final_score} DECIMAL(5,2) 一致）。 */
    static double finalScore(CalibrationSample sample, Map<FactorCode, Double> weights) {
        double weighted = 0;
        double weightSum = 0;
        for (Map.Entry<FactorCode, Double> value : sample.normalizedValues().entrySet()) {
            Double weight = weights.get(value.getKey());
            if (weight != null) {
                weighted += weight * value.getValue();
                weightSum += weight;
            }
        }
        double bonus = weightSum <= 0 ? 0 : weighted / weightSum;
        double score = Math.max(0, bonus - sample.penaltySubtotal());
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    static Grade grade(double score, double penaltySubtotal, WeightScheme.GradeCut cut) {
        Grade grade = score >= cut.gradeAMin() ? Grade.A : score >= cut.gradeBMin() ? Grade.B : Grade.C;
        boolean suppressed = penaltySubtotal >= FactorCode.PENALTY_GRADE_SUPPRESS_THRESHOLD;
        return suppressed && grade == Grade.A ? Grade.B : grade;
    }

    /**
     * @param correlation Spearman；樣本不足 3 筆或分數無變異時為 null
     * @param pearson 供與 S-12 對照（§FR-11-3 用 Pearson）；樣本不足 2 筆或無變異時為 null
     * @param gradeAHitRate 重算後沒有任何 A 級時為 null
     */
    public record Outcome(
            String code,
            String label,
            Long versionId,
            int sampleSize,
            Double correlation,
            Double pearson,
            int gradeACount,
            int gradeAHitCount,
            Double gradeAHitRate) {
    }
}
