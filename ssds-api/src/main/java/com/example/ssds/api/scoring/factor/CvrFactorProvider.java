package com.example.ssds.api.scoring.factor;

import com.example.ssds.core.domain.FactorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** §5.2.3 CVR：自身轉換率、同品類中位數填補及無曝光量時的相對表現。 */
public class CvrFactorProvider {
    private static final int MIN_CATEGORY_SAMPLES = 10;

    public FactorComputation provide(CvrEvidence evidence, PercentileBasis basis) {
        if (evidence == null || evidence.productHistory() == null || evidence.categoryHistories() == null) {
            return FactorComputation.unavailableBonus(FactorCode.CVR, "缺少銷售資料");
        }
        BigDecimal own = conversion(evidence.productHistory());
        if (own != null) {
            return FactorComputation.bonus(FactorCode.CVR, own, basis, false, "使用品項自身開團紀錄");
        }
        if (evidence.categoryHistories().size() < MIN_CATEGORY_SAMPLES) {
            return FactorComputation.unavailableBonus(FactorCode.CVR, "同品類有效歷史少於 10 筆");
        }

        List<BigDecimal> categoryConversions = evidence.categoryHistories().stream()
                .map(CvrFactorProvider::conversion)
                .filter(value -> value != null)
                .sorted()
                .toList();
        if (!categoryConversions.isEmpty()) {
            BigDecimal median = median(categoryConversions);
            return FactorComputation.bonus(
                    FactorCode.CVR, median, basis, true, "無自身曝光紀錄，以同品類轉換率中位數填補");
        }

        int ownQty = evidence.productHistory().stream().mapToInt(SalesSample::qty).sum();
        List<Integer> categoryQty = evidence.categoryHistories().stream()
                .map(history -> history.stream().mapToInt(SalesSample::qty).sum())
                .toList();
        BigDecimal averageQty = BigDecimal.valueOf(categoryQty.stream().mapToInt(Integer::intValue).average().orElse(0));
        if (ownQty <= 0 || averageQty.signum() <= 0) {
            return FactorComputation.unavailableBonus(FactorCode.CVR, "曝光數與可比較銷量皆不足");
        }
        BigDecimal relative = BigDecimal.valueOf(ownQty).divide(averageQty, 6, RoundingMode.HALF_UP);
        return FactorComputation.bonus(
                FactorCode.CVR, relative, basis, false, "曝光數未提供，以品項銷量／同品類平均銷量計算");
    }

    private static BigDecimal conversion(List<SalesSample> history) {
        long impressions = history.stream()
                .filter(sample -> sample.impression() != null)
                .mapToLong(SalesSample::impression)
                .sum();
        if (impressions <= 0) return null;
        long qty = history.stream().mapToLong(SalesSample::qty).sum();
        return BigDecimal.valueOf(qty).divide(BigDecimal.valueOf(impressions), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal median(List<BigDecimal> sorted) {
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return sorted.get(middle);
        return sorted.get(middle - 1).add(sorted.get(middle))
                .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    public record SalesSample(int qty, Integer impression) {
        public SalesSample {
            if (qty < 0 || (impression != null && impression < 0)) {
                throw new IllegalArgumentException("銷量與曝光數不得為負數");
            }
        }
    }

    public record CvrEvidence(
            List<SalesSample> productHistory,
            List<List<SalesSample>> categoryHistories) {
        public CvrEvidence {
            productHistory = productHistory == null ? null : List.copyOf(productHistory);
            if (categoryHistories != null) {
                List<List<SalesSample>> copy = new ArrayList<>();
                categoryHistories.forEach(history -> copy.add(List.copyOf(history)));
                categoryHistories = List.copyOf(copy);
            }
        }
    }
}
