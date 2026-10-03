package com.example.ssds.api.scoring.factor;

import com.example.ssds.core.domain.FactorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** §5.2.4 PRICE_FIT：售價與去識別化客群價格帶的加權適配度。 */
public class PriceFitFactorProvider {
    public FactorComputation provide(
            BigDecimal suggestedPrice, List<AudienceBand> audienceMix, PercentileBasis basis) {
        if (suggestedPrice == null || audienceMix == null || audienceMix.isEmpty()) {
            return FactorComputation.unavailableBonus(FactorCode.PRICE_FIT, "缺少售價或客群價格帶");
        }
        if (suggestedPrice.signum() < 0) throw new IllegalArgumentException("售價不得為負數");
        if (audienceMix.stream().anyMatch(band -> band.share() == null
                || band.share().signum() < 0 || band.share().compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("客群價格帶 share 必須介於 0 到 1");
        }
        BigDecimal shareSum = audienceMix.stream()
                .map(AudienceBand::share)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (shareSum.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("客群價格帶 share 合計必須為 1.000");
        }
        BigDecimal raw = audienceMix.stream()
                .map(band -> band.share().multiply(fit(suggestedPrice, band)))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(6, RoundingMode.HALF_UP);
        return FactorComputation.bonus(FactorCode.PRICE_FIT, raw, basis, false, null);
    }

    private static BigDecimal fit(BigDecimal price, AudienceBand segment) {
        if (segment.priceMin() == null || segment.priceMax() == null
                || segment.priceMax().compareTo(segment.priceMin()) <= 0) {
            throw new IllegalArgumentException("客群價格帶上限必須大於下限");
        }
        if (price.compareTo(segment.priceMin()) >= 0 && price.compareTo(segment.priceMax()) <= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal distance = price.compareTo(segment.priceMin()) < 0
                ? segment.priceMin().subtract(price)
                : price.subtract(segment.priceMax());
        BigDecimal halfBand = segment.priceMax().subtract(segment.priceMin())
                .multiply(new BigDecimal("0.5"));
        return BigDecimal.ONE.subtract(distance.divide(halfBand, 6, RoundingMode.HALF_UP))
                .max(BigDecimal.ZERO);
    }

    public record AudienceBand(
            String audienceCode, BigDecimal priceMin, BigDecimal priceMax, BigDecimal share) {}
}
