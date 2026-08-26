package com.example.ssds.api.product.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.dao.SourcingHeatSignalDao;
import com.example.ssds.infra.dao.projection.SourcingHeatSignal;
import com.example.ssds.infra.entity.CategoryLeadTime;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.CategoryLeadTimeRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 建立或同步 B 軌尋源候選，並依最新熱度訊號重算時效落差。 */
@Service
@Transactional
public class ProductSourcingCandidateService {

    private final SourcingCandidateRepository candidateRepository;
    private final CategoryLeadTimeRepository leadTimeRepository;
    private final SourcingHeatSignalDao heatSignalDao;

    public ProductSourcingCandidateService(
            SourcingCandidateRepository candidateRepository,
            CategoryLeadTimeRepository leadTimeRepository,
            SourcingHeatSignalDao heatSignalDao
    ) {
        this.candidateRepository = candidateRepository;
        this.leadTimeRepository = leadTimeRepository;
        this.heatSignalDao = heatSignalDao;
    }

    public void synchronize(Product product) {
        if (product.getTrackType() != TrackType.B) {
            return;
        }

        CategoryLeadTime categoryLeadTime = leadTimeRepository
                .findById(product.getCategory().getId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VALIDATION_FAILED,
                        "品項類別尚未設定尋源前置天數：" + product.getCategory().getId()
                ));

        SourcingCandidate candidate = candidateRepository
                .findByProductId(product.getId())
                .orElseGet(() -> SourcingCandidate.builder()
                        .product(product)
                        .leadTimeDays(categoryLeadTime.getLeadTimeDays())
                        .build());

        candidate.setProduct(product);
        candidate.setCategory(product.getCategory());
        if (candidate.getLeadTimeOverriddenBy() == null) {
            candidate.setLeadTimeDays(categoryLeadTime.getLeadTimeDays());
        }

        Set<Long> keywordIds = product.getKeywords().stream()
                .map(TrendKeyword::getId)
                .collect(Collectors.toSet());
        Map<Long, TrendKeyword> keywordsById = product.getKeywords().stream()
                .collect(Collectors.toMap(TrendKeyword::getId, Function.identity()));

        heatSignalDao.findLatest(keywordIds).ifPresentOrElse(
                signal -> applySignal(candidate, signal, keywordsById),
                () -> clearSignal(candidate, product)
        );

        candidate.recalculateTimeGap();
        candidateRepository.saveAndFlush(candidate);
    }

    private void applySignal(
            SourcingCandidate candidate,
            SourcingHeatSignal signal,
            Map<Long, TrendKeyword> keywordsById
    ) {
        candidate.setKeyword(keywordsById.get(signal.keywordId()));
        candidate.setHeatStage(signal.heatStage());
        candidate.setStageWeeks(signal.stageWeeks());
        candidate.setEstimatedLifespanDays(signal.estimatedLifespanDays());
    }

    private void clearSignal(SourcingCandidate candidate, Product product) {
        candidate.setKeyword(product.getKeywords().stream()
                .min(Comparator.comparing(TrendKeyword::getId))
                .orElse(null));
        candidate.setHeatStage(null);
        candidate.setStageWeeks(null);
        candidate.setEstimatedLifespanDays(null);
        candidate.setTimeGapDays(null);
    }
}
