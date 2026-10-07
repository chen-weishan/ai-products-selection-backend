package com.example.ssds.api.report.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import com.example.ssds.api.report.dto.ReportGenerateRequest;
import com.example.ssds.core.domain.ReportFormat;
import com.example.ssds.core.domain.ReportType;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.CalibrationStatus;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ReportRequestValidator {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");

    public Map<String, Object> validateAndNormalize(ReportGenerateRequest request) {
        validateFormat(request.reportType(), request.format());
        Map<String, Object> normalized = new LinkedHashMap<>();
        Map<String, Object> raw = request.params();
        switch (request.reportType()) {
            case WEEKLY_PICK, SCORE_DETAIL -> {
                normalized.put("period", period(raw.get("period")));
                optionalPositiveLong(raw, normalized, "categoryId");
            }
            case ACCURACY -> {
                LocalDate to = date(raw.get("to"), LocalDate.now(BUSINESS_ZONE), "to");
                LocalDate from = date(raw.get("from"), to.minusMonths(12), "from");
                if (from.isAfter(to)) {
                    throw validation("from", "from 不可晚於 to");
                }
                normalized.put("from", from.toString());
                normalized.put("to", to.toString());
                optionalPositiveLong(raw, normalized, "categoryId");
                optionalPositiveLong(raw, normalized, "decisionMakerId");
            }
            case SOURCING_QUEUE -> {
                optionalPositiveLong(raw, normalized, "categoryId");
                String status = text(raw.get("status"));
                if (status != null) {
                    try {
                        normalized.put("status", SourcingStatus.valueOf(status).name());
                    } catch (IllegalArgumentException exception) {
                        throw validation("status", "未知的尋源狀態");
                    }
                }
            }
            case CALIBRATION -> {
                optionalQuarter(raw, normalized, "fromQuarter");
                optionalQuarter(raw, normalized, "toQuarter");
                if (normalized.containsKey("fromQuarter") && normalized.containsKey("toQuarter")
                        && normalized.get("fromQuarter").toString()
                        .compareTo(normalized.get("toQuarter").toString()) > 0) {
                    throw validation("fromQuarter", "起始季度不可晚於結束季度");
                }
                String status = text(raw.get("status"));
                if (status != null) {
                    try {
                        normalized.put("status", CalibrationStatus.valueOf(status).name());
                    } catch (IllegalArgumentException exception) {
                        throw validation("status", "未知的校準狀態");
                    }
                }
            }
        }
        return Map.copyOf(normalized);
    }

    private void validateFormat(ReportType type, ReportFormat format) {
        ReportFormat expected = switch (type) {
            case SCORE_DETAIL, SOURCING_QUEUE -> ReportFormat.XLSX;
            case WEEKLY_PICK, ACCURACY, CALIBRATION -> ReportFormat.PDF;
        };
        if (format != expected) {
            throw validation("format", type + " 僅支援 " + expected);
        }
    }

    private String period(Object value) {
        String text = text(value);
        if (text == null) {
            LocalDate today = LocalDate.now(BUSINESS_ZONE);
            WeekFields fields = WeekFields.ISO;
            return "%04dW%02d".formatted(
                    today.get(fields.weekBasedYear()), today.get(fields.weekOfWeekBasedYear()));
        }
        if (!text.matches("\\d{4}W(0[1-9]|[1-4]\\d|5[0-3])")) {
            throw validation("period", "period 必須為 ISO 週格式，例如 2026W30");
        }
        return text;
    }

    private LocalDate date(Object value, LocalDate defaultValue, String field) {
        String text = text(value);
        if (text == null) {
            return defaultValue;
        }
        try {
            return LocalDate.parse(text);
        } catch (java.time.format.DateTimeParseException exception) {
            throw validation(field, field + " 必須為 yyyy-MM-dd");
        }
    }

    private void optionalPositiveLong(
            Map<String, Object> raw, Map<String, Object> target, String field) {
        Object value = raw.get(field);
        if (value == null || text(value) == null) {
            return;
        }
        try {
            long number = value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
            if (number <= 0) {
                throw new NumberFormatException();
            }
            target.put(field, number);
        } catch (NumberFormatException exception) {
            throw validation(field, field + " 必須為正整數");
        }
    }

    private void optionalQuarter(Map<String, Object> raw, Map<String, Object> target, String field) {
        String value = text(raw.get(field));
        if (value == null) {
            return;
        }
        if (!value.matches("\\d{4}Q[1-4]")) {
            throw validation(field, field + " 必須為 YYYYQ1～YYYYQ4");
        }
        target.put(field, value);
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = value.toString().trim().toUpperCase(Locale.ROOT);
        return result.isEmpty() ? null : result;
    }

    private BusinessException validation(String field, String message) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "報表條件驗證失敗",
                List.of(new FieldError(field, message)));
    }
}
