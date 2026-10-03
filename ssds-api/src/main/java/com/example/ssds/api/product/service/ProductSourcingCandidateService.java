package com.example.ssds.api.product.service;

import com.example.ssds.api.sourcing.SourcingDrivingHeatSelector;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.CategoryLeadTime;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.CategoryLeadTimeRepository;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 建立或同步 B 軌尋源候選，並依最新熱度訊號重算時效落差。 */
@Service
@Transactional
public class ProductSourcingCandidateService {

    private final SourcingCandidateRepository candidateRepository;
    private final CategoryLeadTimeRepository leadTimeRepository;
    private final HeatCompositeDailyRepository heatComposites;

    public ProductSourcingCandidateService(
            SourcingCandidateRepository candidateRepository,
            CategoryLeadTimeRepository leadTimeRepository,
            HeatCompositeDailyRepository heatComposites
    ) {
        this.candidateRepository = candidateRepository;
        this.leadTimeRepository = leadTimeRepository;
        this.heatComposites = heatComposites;
    }

    public void synchronize(Product product) {
        if (product.getTrackType() != TrackType.B) {
            return;
        }

        Optional<SourcingCandidate> existingCandidate =
                candidateRepository.findByProductId(product.getId());
        if (existingCandidate.isPresent()
                && product.getSourcingStatus() == SourcingStatus.PROMOTED) {
            return;
        }

        CategoryLeadTime categoryLeadTime = leadTimeRepository
                .findById(product.getCategory().getId())
                .orElse(null);
        if (categoryLeadTime == null) {
            /*
             * FR-03-2 只要求 B 軌具備名稱、類別與關聯關鍵字。
             * 前置天數屬於 FR-17 的參照資料；尚未維護時仍允許品項
             * 建檔，清單以「—」顯示時效落差，待參照資料齊全後再同步。
             */
            existingCandidate.ifPresent(candidateRepository::delete);
            return;
        }

        SourcingCandidate candidate = existingCandidate
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
        Map<Long, HeatCompositeDaily> latestByKeyword = keywordIds.isEmpty()
                ? Map.of()
                : heatComposites.findLatestEligibleForDrivingKeyword(keywordIds).stream()
                        .collect(Collectors.toMap(value -> value.getKeyword().getId(), value -> value));
        HeatCompositeDaily signal = SourcingDrivingHeatSelector.select(
                product.getKeywords(), latestByKeyword);
        if (signal != null) {
            applySignal(candidate, signal);
        } else {
            clearSignal(candidate, product);
        }

        candidateRepository.saveAndFlush(candidate);
    }

    private void applySignal(
            SourcingCandidate candidate,
            HeatCompositeDaily signal
    ) {
        TrendKeyword drivingKeyword = signal.getKeyword();
        candidate.setDrivingKeyword(drivingKeyword);
        if (candidate.getKeyword() == null) {
            candidate.setKeyword(drivingKeyword);
        }
        candidate.recalculateTimeGap(signal.getEstimatedLifespanDays());
    }

    private void clearSignal(SourcingCandidate candidate, Product product) {
        if (candidate.getKeyword() == null) {
            candidate.setKeyword(product.getKeywords().stream()
                    .min(Comparator.comparing(TrendKeyword::getId))
                    .orElse(null));
        }
        // 沒有新的合格訊號時保留最後一次快照；未知資料不得讓已加入優先序者退回 PENDING。
    }
}
