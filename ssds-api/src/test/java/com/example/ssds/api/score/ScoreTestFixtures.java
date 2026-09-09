package com.example.ssds.api.score;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.GradeThreshold;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.entity.WeightProfile;
import com.example.ssds.infra.entity.WeightVersion;

/**
 * FR-04 測試共用的建構子。
 *
 * <p>刻意不用 @SpringBootTest：這裡驗的是計分規則（§5.5 加權、§5.6 分級與壓級、
 * §5.7 分攤），與資料庫無關。用真資料庫測會分不出「規則算對了」還是
 * 「查詢剛好回這個值」。
 */
final class ScoreTestFixtures {

    /** §5.5 黃金案例的話題爆款型權重，加總 1.000。 */
    static final List<BigDecimal> VIRAL_WEIGHTS = List.of(
            new BigDecimal("0.50"), new BigDecimal("0.10"), new BigDecimal("0.08"),
            new BigDecimal("0.07"), new BigDecimal("0.15"), new BigDecimal("0.10"));

    static final FactorCode[] BONUS_FACTORS = {
            FactorCode.TREND, FactorCode.MARGIN, FactorCode.CVR,
            FactorCode.PRICE_FIT, FactorCode.FESTIVAL, FactorCode.CLIMATE };

    private ScoreTestFixtures() {
        throw new AssertionError("工具類別，不應被實例化");
    }

    static Product product(long id, String name) {
        return Product.builder()
                .id(id)
                .name(name)
                .category(Category.builder().id(1L).name("零食").build())
                .build();
    }

    static ProductScore score(long id, BigDecimal penaltySubtotal) {
        return ProductScore.builder()
                .id(id)
                .product(product(id, "品項 " + id))
                .period("2026W30")
                .sceneType(SceneType.VIRAL)
                .primary(true)
                .active(true)
                .bonusSubtotal(new BigDecimal("86.89"))
                .penaltySubtotal(penaltySubtotal)
                .finalScore(new BigDecimal("82.89"))
                .grade(Grade.B)
                .confidence(86)
                .build();
    }

    /** 六個加分因子都有資料，正規化值一律 100 → 加權後恰為 100。 */
    static List<ScoreFactor> allBonusFactors(ProductScore score) {
        return Arrays.stream(BONUS_FACTORS)
                .map(code -> bonusFactor(score, code, new BigDecimal("100"), true))
                .toList();
    }

    static ScoreFactor bonusFactor(
            ProductScore score, FactorCode code, BigDecimal normalized, boolean dataAvailable) {
        return ScoreFactor.builder()
                .score(score)
                .factorCode(code)
                .normalizedValue(dataAvailable ? normalized : null)
                .weight(new BigDecimal("0.10"))
                .penalty(false)
                .dataAvailable(dataAvailable)
                .build();
    }

    static ScoreFactor penaltyFactor(
            ProductScore score, FactorCode code, BigDecimal penaltyValue, boolean dataAvailable) {
        return ScoreFactor.builder()
                .score(score)
                .factorCode(code)
                .penaltyValue(dataAvailable ? penaltyValue : null)
                .rawValue(new BigDecimal("0.10"))
                .penalty(true)
                .dataAvailable(dataAvailable)
                .build();
    }

    /** 四組情境權重都齊的版本，每組加總 1.000。 */
    static WeightVersion weightVersion(long id) {
        WeightVersion version = WeightVersion.builder()
                .id(id)
                .versionNo("v3")
                .name("2026 夏季｜基準")
                .build();

        for (SceneType scene : SceneType.values()) {
            for (int i = 0; i < BONUS_FACTORS.length; i++) {
                version.getProfiles().add(WeightProfile.builder()
                        .version(version)
                        .sceneType(scene)
                        .factorCode(BONUS_FACTORS[i])
                        .weight(VIRAL_WEIGHTS.get(i))
                        .build());
            }
        }
        return version;
    }

    static GradeThreshold threshold(SceneType scene, String aMin, String bMin) {
        return GradeThreshold.builder()
                .sceneType(scene)
                .gradeAMin(new BigDecimal(aMin))
                .gradeBMin(new BigDecimal(bMin))
                .build();
    }
}
