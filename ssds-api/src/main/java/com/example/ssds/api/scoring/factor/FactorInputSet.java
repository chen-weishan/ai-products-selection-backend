package com.example.ssds.api.scoring.factor;

import com.example.ssds.api.scoring.ScoreEvaluationService.FactorInput;
import com.example.ssds.core.domain.FactorCode;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/** 將九個 provider 結果收斂成 {@code ScoreEvaluationService} 的完整輸入。 */
public final class FactorInputSet {
    private FactorInputSet() {}

    public static Map<FactorCode, FactorInput> from(Collection<FactorComputation> computations) {
        EnumMap<FactorCode, FactorInput> result = new EnumMap<>(FactorCode.class);
        for (FactorComputation computation : computations) {
            if (result.put(computation.code(), computation.toEvaluationInput()) != null) {
                throw new IllegalArgumentException("重複因子：" + computation.code());
            }
        }
        if (result.size() != FactorCode.values().length) {
            throw new IllegalArgumentException("必須提供完整九因子計算結果");
        }
        return Map.copyOf(result);
    }
}
