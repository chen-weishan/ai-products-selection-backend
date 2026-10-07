package com.example.ssds.api.risk;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.ssds.infra.dao.RiskRuleDao;
import com.example.ssds.infra.dao.RiskRuleDao.RiskRuleData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 示警門檻（§FR-10-1，存於 {@code risk_rule}）的強型別讀取。
 *
 * <p>與 {@code ScoringRiskRuleService}（扣分三類）不同：缺規則時<b>不拋例外</b>，
 * 退回規格書的預設值並記一筆警告。扣分規則缺了，分數本身算不出來，必須擋下；
 * 示警門檻缺了，只是少一種提醒，不該連帶讓整次評分或整批熱度偵測失敗。
 * 欄位缺少、型別不對或值域不合理（例如急墜門檻設成正數）同樣退回預設。
 *
 * <p>{@code PENALTY_CAP} 不在這裡：它的 20 分是 §5.6 分級壓級的硬規則，
 * 固定值、不開放調整（見 {@code RiskAlertEvaluator}）。
 */
@Service
public class RiskAlertRuleService {

    private static final Logger log = LoggerFactory.getLogger(RiskAlertRuleService.class);

    /** §FR-10-1 LOW_CONFIDENCE：信心度 &lt; 50。 */
    public static final int DEFAULT_LOW_CONFIDENCE_THRESHOLD = 50;

    /** §FR-10-1 HEAT_CRASH：{@code slope_7d} ≤ −40%。 */
    public static final BigDecimal DEFAULT_HEAT_CRASH_SLOPE = new BigDecimal("-0.40");

    /** §FR-10-1 HEAT_SURGE：同品類當日 {@code slope_7d} 分佈的 P95（以 0–1 的比例存放）。 */
    public static final BigDecimal DEFAULT_HEAT_SURGE_PERCENTILE = new BigDecimal("0.95");

    /** §FR-10-1 SEASON_MISMATCH：季節氣候適配百分位 &lt; 20。 */
    public static final BigDecimal DEFAULT_SEASON_MISMATCH_PERCENTILE = new BigDecimal("20");

    public static final BigDecimal DEFAULT_PENALTY_CAP_THRESHOLD = BigDecimal.valueOf(20);

    /** §FR-10-1 FESTIVAL_WINDOW_CLOSING：窗權重將於 7 日內降低。 */
    public static final int DEFAULT_FESTIVAL_WINDOW_CLOSING_DAYS = 7;

    private final RiskRuleDao riskRuleDao;
    private final ObjectMapper objectMapper;

    public RiskAlertRuleService(RiskRuleDao riskRuleDao, ObjectMapper objectMapper) {
        this.riskRuleDao = riskRuleDao;
        this.objectMapper = objectMapper;
    }

    /** 信心度低於此值開立 LOW_CONFIDENCE。品類有覆寫時以覆寫為準。 */
    public int lowConfidenceThreshold(Long categoryId) {
        JsonNode value = field(RiskTypes.LOW_CONFIDENCE, categoryId, "confidenceThreshold",
                JsonNode::isIntegralNumber, String.valueOf(DEFAULT_LOW_CONFIDENCE_THRESHOLD));
        return value == null ? DEFAULT_LOW_CONFIDENCE_THRESHOLD : value.intValue();
    }

    /**
     * {@code slope_7d} 小於等於此值開立 HEAT_CRASH（必為負數，預設 −0.40）。
     * 設成 0 或正數會讓幾乎所有品項都觸發，視為格式錯誤而退回預設。
     */
    public BigDecimal heatCrashSlopeThreshold(Long categoryId) {
        JsonNode value = field(RiskTypes.HEAT_CRASH, categoryId, "slope7dThreshold",
                node -> node.isNumber() && node.decimalValue().signum() < 0,
                DEFAULT_HEAT_CRASH_SLOPE.toPlainString());
        return value == null ? DEFAULT_HEAT_CRASH_SLOPE : value.decimalValue();
    }

    /**
     * 品項 {@code slope_7d} 落在同品類分佈的第幾個百分位以上開立 HEAT_SURGE，
     * 以 0–1 的比例表示（預設 0.95 即 P95）。必須落在 (0, 1]。
     */
    public BigDecimal heatSurgePercentile(Long categoryId) {
        JsonNode value = field(RiskTypes.HEAT_SURGE, categoryId, "slopePercentile",
                node -> node.isNumber()
                        && node.decimalValue().signum() > 0
                        && node.decimalValue().compareTo(BigDecimal.ONE) <= 0,
                DEFAULT_HEAT_SURGE_PERCENTILE.toPlainString());
        return value == null ? DEFAULT_HEAT_SURGE_PERCENTILE : value.decimalValue();
    }

    /**
     * 氣候適配百分位<b>嚴格小於</b>此值開立 SEASON_MISMATCH（預設 20，0–100 的百分位）。
     * 必須落在 (0, 100]；0 以下永遠不會觸發、超過 100 會讓所有品項都觸發，皆視為格式錯誤而退回預設。
     */
    public BigDecimal seasonMismatchPercentileThreshold(Long categoryId) {
        JsonNode value = field(RiskTypes.SEASON_MISMATCH, categoryId, "climateFitPercentileThreshold",
                node -> node.isNumber()
                        && node.decimalValue().signum() > 0
                        && node.decimalValue().compareTo(BigDecimal.valueOf(100)) <= 0,
                DEFAULT_SEASON_MISMATCH_PERCENTILE.toPlainString());
        return value == null ? DEFAULT_SEASON_MISMATCH_PERCENTILE : value.decimalValue();
    }

    /**
     * 檔期窗權重將於幾日內由 1.0 降低就開立 FESTIVAL_WINDOW_CLOSING（預設 7，含邊界）。
     * 欄位沿用 seed 的 {@code daysBeforeLeadTimeCutoff}。必須是 1–30 的整數：
     * 0 以下永遠不會觸發，超過 30 已大於黃金備貨期長度，等於整段備貨期都在示警。
     */
    public int festivalWindowClosingDays(Long categoryId) {
        JsonNode value = field(RiskTypes.FESTIVAL_WINDOW_CLOSING, categoryId, "daysBeforeLeadTimeCutoff",
                node -> node.isIntegralNumber() && node.intValue() >= 1 && node.intValue() <= 30,
                String.valueOf(DEFAULT_FESTIVAL_WINDOW_CLOSING_DAYS));
        return value == null ? DEFAULT_FESTIVAL_WINDOW_CLOSING_DAYS : value.intValue();
    }
    
       /** 扣分壓級示警門檻，分級硬規則本身仍由評分引擎固定於 20 分。 */
    public BigDecimal penaltyCapThreshold(Long categoryId) {
        JsonNode value = field(RiskTypes.PENALTY_CAP, categoryId, "penaltySubtotalThreshold",
                node -> node.isNumber()
                        && node.decimalValue().signum() >= 0
                        && node.decimalValue().compareTo(BigDecimal.valueOf(40)) <= 0,
                DEFAULT_PENALTY_CAP_THRESHOLD.toPlainString());
        return value == null ? DEFAULT_PENALTY_CAP_THRESHOLD : value.decimalValue();
    }


    /**
     * 讀出規則 JSON 裡的指定欄位。缺規則、JSON 壞掉、欄位缺少或不合格時回傳 null，
     * 由呼叫端套預設值；每一種退路都會記警告，讓「門檻默默變回預設」查得到原因。
     */
    private JsonNode field(
            String ruleCode, Long categoryId, String fieldName, Predicate<JsonNode> valid, String defaultText) {
        Optional<RiskRuleData> rule = riskRuleDao.findEffective(ruleCode, categoryId);
        if (rule.isEmpty()) {
            log.warn("缺少有效的 {} 規則，改用規格書預設 {}", ruleCode, defaultText);
            return null;
        }
        try {
            JsonNode value = objectMapper.readTree(rule.get().thresholdJson()).get(fieldName);
            if (value != null && valid.test(value)) {
                return value;
            }
        } catch (Exception exception) {
            log.warn("{} 規則 JSON 無法解析", ruleCode, exception);
        }
        log.warn("{} 規則的欄位 {} 缺少或不合格，改用規格書預設 {}", ruleCode, fieldName, defaultText);
        return null;
    }
}
