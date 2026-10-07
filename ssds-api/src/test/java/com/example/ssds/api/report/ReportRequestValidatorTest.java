package com.example.ssds.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.report.dto.ReportGenerateRequest;
import com.example.ssds.api.report.service.ReportRequestValidator;
import com.example.ssds.core.domain.ReportFormat;
import com.example.ssds.core.domain.ReportType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportRequestValidatorTest {
    private final ReportRequestValidator validator = new ReportRequestValidator();

    @Test
    void normalizesAccuracyFilters() {
        Map<String, Object> result = validator.validateAndNormalize(new ReportGenerateRequest(
                ReportType.ACCURACY,
                ReportFormat.PDF,
                Map.of("from", "2026-01-01", "to", "2026-09-30",
                        "categoryId", 8, "decisionMakerId", "3")));

        assertEquals("2026-01-01", result.get("from"));
        assertEquals("2026-09-30", result.get("to"));
        assertEquals(8L, result.get("categoryId"));
        assertEquals(3L, result.get("decisionMakerId"));
    }

    @Test
    void rejectsWrongFormatForReportType() {
        assertThrows(BusinessException.class, () -> validator.validateAndNormalize(
                new ReportGenerateRequest(
                        ReportType.SCORE_DETAIL, ReportFormat.PDF, Map.of("period", "2026W30"))));
    }

    @Test
    void rejectsInvalidDateRange() {
        assertThrows(BusinessException.class, () -> validator.validateAndNormalize(
                new ReportGenerateRequest(
                        ReportType.ACCURACY, ReportFormat.PDF,
                        Map.of("from", "2026-10-01", "to", "2026-01-01"))));
    }

    @Test
    void validatesQuarterAndStatus() {
        Map<String, Object> result = validator.validateAndNormalize(new ReportGenerateRequest(
                ReportType.CALIBRATION,
                ReportFormat.PDF,
                Map.of("fromQuarter", "2025q4", "toQuarter", "2026q3", "status", "approved")));

        assertEquals("2025Q4", result.get("fromQuarter"));
        assertEquals("2026Q3", result.get("toQuarter"));
        assertEquals("APPROVED", result.get("status"));
    }
}
