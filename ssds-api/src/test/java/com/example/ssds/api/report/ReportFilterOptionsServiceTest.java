package com.example.ssds.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.example.ssds.api.report.dto.ReportFilterOptionsResponse;
import com.example.ssds.api.report.service.ReportFilterOptionsService;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.repository.CalibrationReportRepository;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.DecisionRecordRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportFilterOptionsServiceTest {
    @Mock private CategoryRepository categories;
    @Mock private DecisionRecordRepository decisions;
    @Mock private ProductScoreRepository productScores;
    @Mock private CalibrationReportRepository calibrationReports;

    @Test
    void returnsNamedCategoriesDecisionMakersAndAvailableQuarters() {
        Category child = Category.builder().id(2L).name("餅乾").sortOrder(1).build();
        Category deleted = Category.builder().id(3L).name("停用類別").deletedAt(
                java.time.Instant.now()).build();
        Category root = Category.builder().id(1L).name("食品")
                .children(List.of(deleted, child)).build();
        AppUser buyer = AppUser.builder().id(7L).displayName("王小明")
                .email("buyer@example.com").build();
        when(categories.findTreeWithChildren()).thenReturn(List.of(root));
        when(decisions.findDistinctDecisionMakers()).thenReturn(List.of(buyer));
        when(productScores.findDistinctActivePeriodsOrderByPeriodDesc())
                .thenReturn(List.of("2026W41", "2026W39", "2025W52"));
        when(calibrationReports.findDistinctQuartersOrderByQuarterDesc())
                .thenReturn(List.of("2026Q3", "2026Q2"));

        ReportFilterOptionsResponse result = new ReportFilterOptionsService(
                categories, decisions, productScores, calibrationReports).get();

        assertEquals(List.of(new ReportFilterOptionsResponse.CategoryOption(2L, "食品／餅乾")),
                result.categories());
        assertEquals("王小明", result.decisionMakers().getFirst().displayName());
        assertEquals(List.of("2026W41", "2026W39", "2025W52"), result.scorePeriods());
        assertEquals(List.of("2026Q3", "2026Q2"), result.calibrationQuarters());
    }
}
