package com.example.ssds.api.risk;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.Severity;

/**
 * §FR-10-1 {@code SEASON_MISMATCH} 判定（純計算，不碰資料庫）。
 *
 * <p>規則：<b>季節氣候適配百分位 &lt; 門檻</b>（預設 20），且品項狀態為 {@code ADOPTED} 或
 * {@code LISTED}；嚴重度 MEDIUM。
 *
 * <ul>
 *   <li><b>嚴格小於</b>：百分位恰為 20 不觸發（與 HEAT_SURGE 的「P95 以上含邊界」方向相反，
 *       規格書字面如此）</li>
 *   <li><b>因子無資料不判定</b>：{@code CLIMATE} 因子 {@code data_available = false}（品項與品類
 *       皆無適溫區間、或缺歷史月均溫）時，§5.7 規定「不扣分」，同理不示警。
 *       不能把 null 當 0——那會讓所有沒設適溫區間的品項都被標成「最不適配」</li>
 *   <li><b>百分位照單全收</b>：評分時若樣本不足退回全品類百分位（§5.3.1），那個百分位仍是
 *       評分實際使用的值，這裡不另行排除；畫面上該因子本來就有「以全品類基準計算」標示</li>
 * </ul>
 */
public final class SeasonMismatchDetector {

    /** 判定所需的最少資訊：品項、品類（取門檻用）、狀態、最新評分的氣候百分位。 */
    public record ProductClimate(
            long productId,
            long categoryId,
            ProductStatus status,
            BigDecimal percentile,
            boolean dataAvailable) {
    }

    public record Detection(long productId, String riskType, Severity severity, String triggerValue) {
    }

    /**
     * @param skippedStatus 狀態不是 ADOPTED／LISTED 而略過
     * @param skippedNoData 氣候因子無資料而略過
     */
    public record Result(List<Detection> detections, int skippedStatus, int skippedNoData) {
    }

    /** 單一品項判定。評分後的即時檢查（AC-10-1）直接呼叫這個。 */
    public Optional<Detection> evaluate(ProductClimate product, BigDecimal threshold) {
        if (!isActive(product.status())) {
            return Optional.empty();
        }
        if (!hasData(product)) {
            return Optional.empty();
        }
        if (product.percentile().compareTo(threshold) >= 0) {
            return Optional.empty();
        }
        return Optional.of(new Detection(
                product.productId(),
                RiskTypes.SEASON_MISMATCH,
                Severity.MEDIUM,
                "季節氣候適配百分位 " + plain(product.percentile()) + "（門檻 " + plain(threshold) + "）"));
    }

    /** 批次判定；門檻以品類為單位由呼叫端提供（品類覆寫值，缺則全域預設）。 */
    public Result detect(List<ProductClimate> products, Function<Long, BigDecimal> thresholdByCategory) {
        List<Detection> detections = new ArrayList<>();
        int skippedStatus = 0;
        int skippedNoData = 0;
        for (ProductClimate product : products) {
            if (!isActive(product.status())) {
                skippedStatus++;
                continue;
            }
            if (!hasData(product)) {
                skippedNoData++;
                continue;
            }
            evaluate(product, thresholdByCategory.apply(product.categoryId())).ifPresent(detections::add);
        }
        return new Result(detections, skippedStatus, skippedNoData);
    }

    private static boolean isActive(ProductStatus status) {
        return status == ProductStatus.ADOPTED || status == ProductStatus.LISTED;
    }

    private static boolean hasData(ProductClimate product) {
        return product.dataAvailable() && product.percentile() != null;
    }

    /** 20.00 → "20"、12.50 → "12.5"（避免 BigDecimal 科學記號與多餘的零）。 */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
