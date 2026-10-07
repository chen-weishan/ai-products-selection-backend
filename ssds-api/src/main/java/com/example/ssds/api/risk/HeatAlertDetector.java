package com.example.ssds.api.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.example.ssds.api.scoring.factor.PercentileBasis;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.Severity;

/**
 * §FR-10-2 熱度異常判定（純計算，不碰資料庫，方便把每個邊界都測到）。
 *
 * <h3>HEAT_CRASH（HIGH）</h3>
 * 品項狀態為 {@code ADOPTED} 或 {@code LISTED}，且 {@code slope_7d ≤ 門檻}（預設 −40%）。
 * 邊界值（恰為 −40%）會觸發。
 *
 * <h3>HEAT_SURGE（MEDIUM）</h3>
 * 品項的 {@code slope_7d} 落在<b>同品類當日分佈</b>的 P95 以上，<b>且</b>熱度量級達下限
 * （§5.2.1-a，即生效關鍵字的 {@code volume_below_floor = false}）。
 * <ul>
 *   <li><b>百分位</b>沿用 §5.3.1 的平均排名法（{@link PercentileBasis}），不另寫一套公式；
 *       母體含品項自己，P95 邊界值（恰為 95.00）會觸發</li>
 *   <li><b>樣本不足的退路</b>比照 §5.3.1：同品類 ≥ 10 直接用；3–9 與同一父品類下的兄弟品類合併；
 *       仍不足（&lt; 3，或沒有父品類可合併）<b>不開立</b>並計入略過數。
 *       不退回全品類，也不退回舊版固定斜率 0.60——2 個樣本的 P95 等於「比另一個高就算爆款」，
 *       寧可略過也不要製造誤報</li>
 *   <li><b>只在 {@code slope_7d > 0} 時開立</b>（規格沒寫，是本實作加的保護）：整個品類都在下滑時，
 *       「跌得最少」的品項也會排在 P95，但它並沒有「竄升」</li>
 * </ul>
 *
 * <h3>品項的 slope_7d</h3>
 * 品項關聯多個關鍵字時取<b>最高者</b>，並記錄生效關鍵字——與 §5.3.3「多對一收斂取最大值」
 * 同一慣例。輸入的 {@link ProductHeat} 已是每品項一筆（生效關鍵字那一筆）。
 */
public final class HeatAlertDetector {

    /** §5.3.1：同品類樣本數達此值才直接使用同品類百分位。 */
    static final int SAMPLE_MIN_SAME_CATEGORY = 10;

    /** §5.3.1：同品類 3–9 時與兄弟品類合併；合併至少需要這麼多同品類樣本。 */
    static final int SAMPLE_MIN_FOR_MERGE = 3;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * @param parentCategoryId 父品類，頂層為 null
     * @param keywordId        生效關鍵字（該品項 {@code slope_7d} 最高者）
     */
    public record ProductHeat(
            long productId,
            long categoryId,
            Long parentCategoryId,
            ProductStatus status,
            long keywordId,
            String keyword,
            BigDecimal slope7d,
            boolean volumeBelowFloor) {
    }

    public record Detection(long productId, String riskType, Severity severity, String triggerValue) {
    }

    /**
     * @param skippedInsufficientSample 斜率為正、但同品類（含合併）樣本不足而未判定 HEAT_SURGE 的品項數
     * @param skippedVolumeFloor        百分位已達標、但熱度量級不足而未開立 HEAT_SURGE 的品項數
     */
    public record Result(List<Detection> detections, int skippedInsufficientSample, int skippedVolumeFloor) {
    }

    /**
     * @param crashSlopeThreshold    各品類的 HEAT_CRASH 斜率門檻（負數）
     * @param surgePercentileFraction 各品類的 HEAT_SURGE 百分位門檻，0–1 的比例（0.95 即 P95）
     */
    public Result detect(
            List<ProductHeat> products,
            Function<Long, BigDecimal> crashSlopeThreshold,
            Function<Long, BigDecimal> surgePercentileFraction) {

        List<Detection> detections = new ArrayList<>();

        for (ProductHeat product : products) {
            if (product.status() != ProductStatus.ADOPTED && product.status() != ProductStatus.LISTED) {
                continue;
            }
            BigDecimal threshold = crashSlopeThreshold.apply(product.categoryId());
            if (product.slope7d().compareTo(threshold) <= 0) {
                detections.add(new Detection(product.productId(), RiskTypes.HEAT_CRASH, Severity.HIGH,
                        "7 日熱度斜率 " + percent(product.slope7d()) + "（門檻 " + percent(threshold)
                                + "）；關鍵字：" + product.keyword()));
            }
        }

        Map<Long, List<ProductHeat>> byCategory = new HashMap<>();
        Map<Long, List<ProductHeat>> byParent = new HashMap<>();
        for (ProductHeat product : products) {
            byCategory.computeIfAbsent(product.categoryId(), id -> new ArrayList<>()).add(product);
            if (product.parentCategoryId() != null) {
                byParent.computeIfAbsent(product.parentCategoryId(), id -> new ArrayList<>()).add(product);
            }
        }

        int skippedSample = 0;
        int skippedFloor = 0;
        for (ProductHeat product : products) {
            if (product.slope7d().signum() <= 0) {
                continue;
            }
            List<ProductHeat> sameCategory = byCategory.get(product.categoryId());
            List<ProductHeat> population;
            String scope;
            if (sameCategory.size() >= SAMPLE_MIN_SAME_CATEGORY) {
                population = sameCategory;
                scope = "同品類";
            } else if (sameCategory.size() >= SAMPLE_MIN_FOR_MERGE && product.parentCategoryId() != null) {
                population = byParent.get(product.parentCategoryId());
                scope = "同父品類合併";
            } else {
                skippedSample++;
                continue;
            }

            BigDecimal percentile = percentileOf(product, population);
            BigDecimal fraction = surgePercentileFraction.apply(product.categoryId());
            BigDecimal threshold = fraction.multiply(HUNDRED);
            if (percentile.compareTo(threshold) < 0) {
                continue;
            }
            if (product.volumeBelowFloor()) {
                skippedFloor++;
                continue;
            }
            detections.add(new Detection(product.productId(), RiskTypes.HEAT_SURGE, Severity.MEDIUM,
                    "7 日熱度斜率 " + percent(product.slope7d()) + "，" + scope + "第 "
                            + percentile.setScale(0, RoundingMode.HALF_UP).toPlainString()
                            + " 百分位（門檻 P" + threshold.stripTrailingZeros().toPlainString()
                            + "）；關鍵字：" + product.keyword()));
        }

        return new Result(List.copyOf(detections), skippedSample, skippedFloor);
    }

    /** 母體含品項自己：{@link PercentileBasis} 的 peers 不含 target，這裡先把自己拿掉。 */
    private static BigDecimal percentileOf(ProductHeat target, List<ProductHeat> population) {
        List<BigDecimal> peers = new ArrayList<>(population.size());
        for (ProductHeat other : population) {
            if (other.productId() != target.productId()) {
                peers.add(other.slope7d());
            }
        }
        return new PercentileBasis(peers, false, null).normalize(target.slope7d());
    }

    /** 比率轉百分比字串，一位小數，正數帶 +：0.25 → "+25.0%"、-0.46 → "-46.0%"。 */
    static String percent(BigDecimal ratio) {
        BigDecimal value = ratio.multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP);
        return (value.signum() > 0 ? "+" : "") + value.toPlainString() + "%";
    }
}
