package com.example.ssds.api.risk;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.security.CurrentUserId;
import com.example.ssds.infra.dao.RiskRuleDao;
import com.example.ssds.infra.dao.RiskRuleDao.RiskRuleRecord;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 風險門檻設定、稽核與變更事件。 */
@Service
public class RiskAlertRuleCommandService {

    private final RiskRuleDao rules;
    private final AuditLogRepository auditLogs;
    private final AppUserRepository users;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    public RiskAlertRuleCommandService(
            RiskRuleDao rules,
            AuditLogRepository auditLogs,
            AppUserRepository users,
            ApplicationEventPublisher events,
            ObjectMapper mapper) {
        this.rules = rules;
        this.auditLogs = auditLogs;
        this.users = users;
        this.events = events;
        this.mapper = mapper;
    }

    public List<RiskRuleRecord> list() {
        return rules.findAllRules();
    }

    @Transactional
    public RiskRuleRecord update(String code, UpdateRequest request) {
        if (!RiskTypes.ALL.contains(code)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到風險規則：" + code);
        }
        Long categoryId = request.categoryId();
        // 請求體以 Map 接收（Jackson 3 可解析），在這裡轉成內部驗證使用的 Jackson 2 JsonNode
        JsonNode threshold = request.threshold() == null ? null : mapper.valueToTree(request.threshold());
        RiskRuleRecord old = rules.findByCodeAndCategory(code, categoryId).orElse(null);
        BigDecimal maxPenalty = request.maxPenalty() == null && old != null
                ? old.maxPenalty() : request.maxPenalty();
        validate(code, threshold, maxPenalty);
        String afterThreshold;
        try {
            afterThreshold = mapper.writeValueAsString(threshold);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "風險門檻格式不正確");
        }
        boolean changed = old == null
                || !jsonEquals(old.thresholdJson(), threshold)
                || compare(old.maxPenalty(), maxPenalty) != 0
                || !old.enabled();
        if (!changed) {
            return old;
        }

        Long userId = CurrentUserId.require();
        rules.upsert(code, categoryId, afterThreshold, maxPenalty, userId);
        RiskRuleRecord updated = rules.findByCodeAndCategory(code, categoryId).orElseThrow();
        auditLogs.save(AuditLog.builder()
                .user(users.getReferenceById(userId))
                .action("UPDATE")
                .entityType("RiskRule")
                .entityId(updated.id())
                .beforeJson(old == null ? null : toAuditJson(old.thresholdJson(), old.maxPenalty()))
                .afterJson(toAuditJson(updated.thresholdJson(), updated.maxPenalty()))
                .build());
        events.publishEvent(new RiskRulesChangedEvent(code));
        return updated;
    }

    private void validate(String code, JsonNode threshold, BigDecimal maxPenalty) {
        if (threshold == null || !threshold.isObject()) {
            throw invalid("threshold 必須是 JSON 物件");
        }
        switch (code) {
            case RiskTypes.REVIEW_RISK -> {
                decimalRange(threshold, "negativeRateThreshold", BigDecimal.ZERO, BigDecimal.ONE);
                integerRange(threshold, "minSampleSize", 1, 100000);
                penaltyRange(maxPenalty, BigDecimal.ZERO, BigDecimal.valueOf(20));
            }
            case RiskTypes.LOGISTICS_RISK -> {
                for (String key : List.of("meltableSummerPoints", "coldChainPoints", "fragilePoints", "oversizedPoints")) {
                    decimalRange(threshold, key, BigDecimal.ZERO, BigDecimal.TEN);
                }
                penaltyRange(maxPenalty, BigDecimal.ZERO, BigDecimal.TEN);
            }
            case RiskTypes.INVENTORY_RISK -> {
                integerRange(threshold, "shelfLifeDaysThreshold", 0, 3650);
                decimalRange(threshold, "shortShelfLifePoints", BigDecimal.ZERO, BigDecimal.TEN);
                decimalRange(threshold, "seasonalPoints", BigDecimal.ZERO, BigDecimal.TEN);
                integerRange(threshold, "moqThreshold", 0, 1000000);
                decimalRange(threshold, "highMoqPoints", BigDecimal.ZERO, BigDecimal.TEN);
                penaltyRange(maxPenalty, BigDecimal.ZERO, BigDecimal.TEN);
            }
            case RiskTypes.HEAT_CRASH -> {
                JsonNode slope = threshold.get("slope7dThreshold");
                if (slope == null || !slope.isNumber()
                        || slope.decimalValue().compareTo(BigDecimal.valueOf(-1)) < 0
                        || slope.decimalValue().signum() >= 0) {
                    throw invalid("slope7dThreshold 必須介於 -1（含）與 0（不含）");
                }
            }
            case RiskTypes.HEAT_SURGE -> decimalRange(threshold, "slopePercentile", BigDecimal.ZERO, BigDecimal.ONE, true);
            case RiskTypes.LOW_CONFIDENCE -> integerRange(threshold, "confidenceThreshold", 0, 100);
            case RiskTypes.SEASON_MISMATCH -> decimalRange(threshold, "climateFitPercentileThreshold", BigDecimal.ZERO, BigDecimal.valueOf(100), true);
            case RiskTypes.FESTIVAL_WINDOW_CLOSING -> integerRange(threshold, "daysBeforeLeadTimeCutoff", 1, 30);
            case RiskTypes.PENALTY_CAP -> {
                JsonNode penaltyThreshold = threshold.get("penaltySubtotalThreshold");
                if (penaltyThreshold == null || !penaltyThreshold.isNumber()
                        || penaltyThreshold.decimalValue().compareTo(BigDecimal.valueOf(20)) != 0) {
                    throw invalid("PENALTY_CAP 的 penaltySubtotalThreshold 固定為 20，不可調整");
                }
                if (maxPenalty != null) throw invalid("PENALTY_CAP 不接受 maxPenalty");
            }
            default -> {
                if (maxPenalty != null) throw invalid("此示警規則不接受 maxPenalty");
                if (threshold.isEmpty()) throw invalid("threshold 不可為空");
            }
        }
    }

    private void decimalRange(JsonNode json, String field, BigDecimal min, BigDecimal max) {
        decimalRange(json, field, min, max, false);
    }

    private void decimalRange(JsonNode json, String field, BigDecimal min, BigDecimal max, boolean minExclusive) {
        JsonNode node = json.get(field);
        if (node == null || !node.isNumber()) throw invalid(field + " 必須是數字");
        BigDecimal value = node.decimalValue();
        int minCmp = value.compareTo(min);
        if ((minExclusive ? minCmp <= 0 : minCmp < 0) || value.compareTo(max) > 0) {
            throw invalid(field + " 超出允許範圍");
        }
    }

    private void integerRange(JsonNode json, String field, int min, int max) {
        JsonNode node = json.get(field);
        if (node == null || !node.isIntegralNumber() || node.intValue() < min || node.intValue() > max) {
            throw invalid(field + " 必須是允許範圍內的整數");
        }
    }

    private void penaltyRange(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw invalid("maxPenalty 超出允許範圍");
        }
    }

    private boolean jsonEquals(String old, JsonNode current) {
        try {
            return mapper.readTree(old).equals(current);
        } catch (Exception exception) {
            return false;
        }
    }

    private static int compare(BigDecimal left, BigDecimal right) {
        if (left == null) return right == null ? 0 : -1;
        return right == null ? 1 : left.compareTo(right);
    }

    private String toAuditJson(String threshold, BigDecimal maxPenalty) {
        return "{\"threshold\":" + threshold + ",\"maxPenalty\":" + (maxPenalty == null ? "null" : maxPenalty.toPlainString()) + "}";
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    /**
     * PUT /risks/rules/{code} 的請求體。
     *
     * <p>threshold 刻意用 {@code Map<String, Object>} 而不是 {@code JsonNode}：Spring 7 的 HTTP 轉換器
     * 使用 Jackson 3（{@code tools.jackson}），無法建構 Jackson 2 的抽象型別
     * {@code com.fasterxml.jackson.databind.JsonNode}，會在進入 Controller 前就回 500。
     */
    public record UpdateRequest(Long categoryId, Map<String, Object> threshold, BigDecimal maxPenalty) {}
}
