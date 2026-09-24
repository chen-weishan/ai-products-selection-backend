package com.example.ssds.infra.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.core.dto.TrendKeywordDetailResponse;
import com.example.ssds.infra.dao.TrendQueryDao;
import com.example.ssds.infra.dao.projection.SourceBreakdownRow;
import com.example.ssds.infra.dao.projection.TrendCompositeSnapshot;
import com.example.ssds.infra.dao.projection.TrendPointRow;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TrendServiceTest {

    @Test
    void detailProvidesNinetyDayAgentAndSourceContract() {
        TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        TrendQueryDao queryDao = mock(TrendQueryDao.class);
        TrendService service = new TrendService(keywords, queryDao);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(89);
        when(keywords.findById(31L)).thenReturn(Optional.of(
                TrendKeyword.builder().id(31L).keyword("抹茶").build()));
        when(queryDao.findTrendRange(List.of(31L), from, today)).thenReturn(List.of(
                new TrendPointRow(31L, "抹茶", from, new BigDecimal("40.00")),
                new TrendPointRow(31L, "抹茶", today, new BigDecimal("70.00"))));
        when(queryDao.findLatestComposite(31L)).thenReturn(Optional.of(
                new TrendCompositeSnapshot(
                        new BigDecimal("70.00"),
                        new BigDecimal("0.20"),
                        new BigDecimal("0.30"),
                        "RISING",
                        3,
                        56,
                        "AGENT",
                        "AGENT",
                        "{\"THREADS\":0.8000,\"INSTAGRAM\":0.2000}",
                        true)));
        when(queryDao.findSourceBreakdown(31L)).thenReturn(List.of(
                new SourceBreakdownRow(
                        "THREADS",
                        "KEYWORD",
                        "AVAILABLE",
                        new BigDecimal("80.00"),
                        new BigDecimal("0.10"),
                        new BigDecimal("0.20")),
                new SourceBreakdownRow(
                        "INSTAGRAM",
                        "CATEGORY",
                        "DEGRADED",
                        new BigDecimal("20.00"),
                        new BigDecimal("0.05"),
                        new BigDecimal("0.08"))));

        TrendKeywordDetailResponse response = service.getKeywordDetail(31L, "90d");

        verify(queryDao).findTrendRange(eq(List.of(31L)), eq(from), eq(today));
        TrendKeywordDetailResponse.SourceDetail instagram = response.getSourceDetails().get(1);
        assertAll(
                () -> assertEquals(2, response.getPoints().size()),
                () -> assertEquals("RISING", response.getStage()),
                () -> assertEquals(3, response.getStageWeeks()),
                () -> assertEquals(56, response.getEstimatedLifespanDays()),
                () -> assertEquals("AGENT", response.getStageSource()),
                () -> assertEquals("AGENT", response.getLifespanSource()),
                () -> assertTrue(response.isDivergenceFlag()),
                () -> assertEquals("CATEGORY", instagram.getGranularity()),
                () -> assertTrue(instagram.isCategoryLevel()),
                () -> assertEquals("DEGRADED", instagram.getStatus()),
                () -> assertEquals(new BigDecimal("0.2000"), instagram.getAppliedWeight()),
                () -> assertEquals(new BigDecimal("0.05"), instagram.getSlope7d()),
                () -> assertEquals(new BigDecimal("0.08"), instagram.getSlope30d()));
    }
}
