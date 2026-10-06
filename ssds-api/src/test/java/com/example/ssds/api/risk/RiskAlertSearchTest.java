package com.example.ssds.api.risk;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.RiskAlertRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** GET /risks 的 keyword：空白視為未搜尋、前後空白裁掉、過長拒絕，且 repository 不會收到 null。 */
class RiskAlertSearchTest {

    private final Pageable pageable = PageRequest.of(0, 20);
    private RiskAlertRepository alerts;
    private RiskAlertCommandService service;

    @BeforeEach
    void setUp() {
        alerts = mock(RiskAlertRepository.class);
        RiskImpactService impacts = mock(RiskImpactService.class);
        when(impacts.describe(any())).thenReturn(Map.of());
        Page<com.example.ssds.infra.entity.RiskAlert> empty = new PageImpl<>(List.of(), pageable, 0);
        when(alerts.findVisible(any(), any())).thenReturn(empty);
        when(alerts.search(any(), any(), any(), any(), any(), any())).thenReturn(empty);
        service = new RiskAlertCommandService(
                alerts, mock(AppUserRepository.class), mock(AuditLogRepository.class), new ObjectMapper(), impacts);
    }

    @Test
    void blankKeywordWithoutOtherFiltersUsesDefaultList() {
        service.search(null, null, null, null, "   ", pageable);

        verify(alerts).findVisible(null, pageable);
        verify(alerts, never()).search(any(), any(), any(), any(), any(), any());
    }

    @Test
    void keywordIsTrimmedAndNeverNull() {
        service.search(null, null, null, null, "  蛋糕 ", pageable);
        verify(alerts).search(null, null, null, null, "蛋糕", pageable);

        service.search(null, null, "HEAT_CRASH", null, null, pageable);
        verify(alerts).search(null, null, "HEAT_CRASH", null, "", pageable);
    }

    @Test
    void tooLongKeywordIsRejected() {
        String tooLong = "a".repeat(RiskAlertCommandService.MAX_KEYWORD_LENGTH + 1);

        assertThatThrownBy(() -> service.search(null, null, null, null, tooLong, pageable))
                .isInstanceOf(BusinessException.class);
        verify(alerts, never()).search(any(), any(), any(), any(), eq(tooLong), any());
    }
}
