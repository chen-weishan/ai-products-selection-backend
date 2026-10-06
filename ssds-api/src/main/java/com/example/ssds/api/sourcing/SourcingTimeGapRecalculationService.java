package com.example.ssds.api.sourcing;

import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** §5.8：以每日合成列純算術重算 B 軌生效關鍵字、時效落差與狀態。 */
@Service
public class SourcingTimeGapRecalculationService {
    private static final Logger log = LoggerFactory.getLogger(SourcingTimeGapRecalculationService.class);
    private final SourcingCandidateRepository candidates;
    private final HeatCompositeDailyRepository composites;

    public SourcingTimeGapRecalculationService(
            SourcingCandidateRepository candidates,
            HeatCompositeDailyRepository composites) {
        this.candidates = candidates;
        this.composites = composites;
    }

    @Transactional
    public int recalculateAll() {
        return recalculateAllExceptDrivingKeywords(List.of());
    }

    /**
     * 每日合成後先重算未進 Agent 5 的候選；有排入 Agent 5 的生效關鍵字，
     * 等覆寫同日壽命後再由 {@link #recalculateAffectedByKeyword} 判定狀態。
     */
    @Transactional
    public int recalculateAllExceptDrivingKeywords(Collection<Long> deferredKeywordIds) {
        return recalculate(
                candidates.findEligibleForTimeGapRecalculation(),
                new HashSet<>(deferredKeywordIds),
                null);
    }

    /** Agent 5 增益層覆寫完成後的立即補算；每日全量作業仍是主要更新路徑。 */
    @Transactional
    public int recalculateAffectedByKeyword(Long keywordId) {
        return recalculate(
                candidates.findEligibleForTimeGapRecalculationByKeywordId(keywordId),
                Set.of(),
                keywordId);
    }

    private int recalculate(
            List<SourcingCandidate> values,
            Set<Long> deferredKeywordIds,
            Long requiredDrivingKeywordId) {
        if (values.isEmpty()) return 0;
        List<Long> keywordIds = values.stream()
                .flatMap(value -> value.getProduct().getKeywords().stream())
                .map(keyword -> keyword.getId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, HeatCompositeDaily> latestByKeyword = keywordIds.isEmpty()
                ? Map.of()
                : composites.findLatestEligibleForDrivingKeyword(keywordIds).stream()
                        .collect(Collectors.toMap(
                                value -> value.getKeyword().getId(), Function.identity()));

        int recalculated = 0;
        for (SourcingCandidate candidate : values) {
            HeatCompositeDaily driving = SourcingDrivingHeatSelector.select(
                    candidate.getProduct().getKeywords(), latestByKeyword);
            if (driving != null) {
                Long drivingKeywordId = driving.getKeyword().getId();
                if (deferredKeywordIds.contains(drivingKeywordId)
                        || (requiredDrivingKeywordId != null
                                && !requiredDrivingKeywordId.equals(drivingKeywordId))) {
                    continue;
                }
                candidate.setDrivingKeyword(driving.getKeyword());
                candidate.recalculateTimeGap(driving.getEstimatedLifespanDays());
                recalculated++;
            }
        }
        candidates.saveAll(values);
        log.info(
                "Sourcing time-gap recalculation completed: candidateCount={}, recalculated={}, deferredDrivingKeywords={}",
                values.size(),
                recalculated,
                deferredKeywordIds.size());
        return recalculated;
    }

}
