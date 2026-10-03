package com.example.ssds.calibration;

import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;

/**
 * Spearman 等級相關係數與雙尾 p 值。
 *
 * <p>選 Spearman 而非 Pearson 是設計決定：標籤是各品項的實際銷量，
 * 不同品類的量級可差百倍（零食 3000 件、家電 30 件），Pearson 會被少數爆量品項主導；
 * 等級相關只看排序，回答的正是「分數越高的是否賣得越多」。
 * 規格 §FR-15 只寫「相關係數」，未指定種類。
 */
public final class RankCorrelation {

    /** 樣本少於此數時相關係數無定義（n − 2 自由度至少為 1）。 */
    public static final int MIN_PAIRS = 3;

    private RankCorrelation() {
    }

    /** @return 相關係數與 p 值；樣本不足或任一變數無變異時回 null */
    public static Result of(double[] x, double[] y) {
        if (x.length != y.length) {
            throw new IllegalArgumentException("x 與 y 長度不同：" + x.length + " vs " + y.length);
        }
        int n = x.length;
        if (n < MIN_PAIRS) {
            return null;
        }
        double r = new SpearmansCorrelation().correlation(x, y);
        if (Double.isNaN(r)) {
            return null;
        }
        return new Result(r, pValue(r, n), n);
    }

    /** t = r·√((n−2)/(1−r²))，自由度 n−2 的雙尾檢定。|r| = 1 時 p = 0。 */
    static double pValue(double r, int n) {
        double denominator = 1 - r * r;
        if (denominator <= 0) {
            return 0;
        }
        double t = Math.abs(r) * Math.sqrt((n - 2) / denominator);
        return 2 * (1 - new TDistribution(n - 2).cumulativeProbability(t));
    }

    public record Result(double correlation, double pValue, int n) {
    }
}
