package com.example.ssds.api.scoring.factor;

import com.example.ssds.core.domain.FactorCode;
import java.math.BigDecimal;

/** §5.2.1 MARGIN：品項毛利率及同批次百分位。 */
public class MarginFactorProvider {
    public FactorComputation provide(BigDecimal marginRate, PercentileBasis basis) {
        if (marginRate == null) {
            return FactorComputation.unavailableBonus(FactorCode.MARGIN, "成本或售價不足，無法計算毛利率");
        }
        if (marginRate.signum() < 0 || marginRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("毛利率必須介於 0 到 1");
        }
        return FactorComputation.bonus(FactorCode.MARGIN, marginRate, basis, false, null);
    }
}
