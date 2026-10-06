package com.example.ssds.api.report.service;

import com.example.ssds.api.report.dto.ReportFilterOptionsResponse;
import com.example.ssds.api.report.dto.ReportFilterOptionsResponse.CategoryOption;
import com.example.ssds.api.report.dto.ReportFilterOptionsResponse.DecisionMakerOption;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.repository.CalibrationReportRepository;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.DecisionRecordRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportFilterOptionsService {
    private final CategoryRepository categories;
    private final DecisionRecordRepository decisions;
    private final ProductScoreRepository productScores;
    private final CalibrationReportRepository calibrationReports;

    public ReportFilterOptionsService(
            CategoryRepository categories,
            DecisionRecordRepository decisions,
            ProductScoreRepository productScores,
            CalibrationReportRepository calibrationReports) {
        this.categories = categories;
        this.decisions = decisions;
        this.productScores = productScores;
        this.calibrationReports = calibrationReports;
    }

    @Transactional(readOnly = true)
    public ReportFilterOptionsResponse get() {
        List<CategoryOption> categoryOptions = new ArrayList<>();
        for (Category root : categories.findTreeWithChildren()) {
            List<Category> children = root.getChildren().stream()
                    .filter(child -> !child.isDeleted())
                    .sorted(Comparator.comparingInt(Category::getSortOrder)
                            .thenComparing(Category::getName))
                    .toList();
            if (children.isEmpty()) {
                categoryOptions.add(new CategoryOption(root.getId(), root.getName()));
            } else {
                children.forEach(child -> categoryOptions.add(new CategoryOption(
                        child.getId(), root.getName() + "／" + child.getName())));
            }
        }

        List<DecisionMakerOption> decisionMakers = decisions.findDistinctDecisionMakers().stream()
                .map(user -> new DecisionMakerOption(
                        user.getId(), user.getDisplayName(), user.getEmail()))
                .toList();

        return new ReportFilterOptionsResponse(
                List.copyOf(categoryOptions),
                decisionMakers,
                productScores.findDistinctActivePeriodsOrderByPeriodDesc(),
                calibrationReports.findDistinctQuartersOrderByQuarterDesc());
    }
}
