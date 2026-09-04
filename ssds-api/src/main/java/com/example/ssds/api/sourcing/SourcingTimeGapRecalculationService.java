package com.example.ssds.api.sourcing;

import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final BigDecimal SLOPE_7D_WEIGHT = new BigDecimal("0.7");
    private static final BigDecimal SLOPE_30D_WEIGHT = new BigDecimal("0.3");

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
        return recalculate(candidates.findEligibleForTimeGapRecalculation());
    }

    /** Agent 5 增益層覆寫完成後的立即補算；每日全量作業仍是主要更新路徑。 */
    @Transactional
    public int recalculateAffectedByKeyword(Long keywordId) {
        return recalculate(candidates.findEligibleForTimeGapRecalculationByKeywordId(keywordId));
    }

    private int recalculate(List<SourcingCandidate> values) {
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

        for (SourcingCandidate candidate : values) {
            HeatCompositeDaily driving = selectDriving(candidate, latestByKeyword);
            candidate.setDrivingKeyword(driving == null ? null : driving.getKeyword());
            candidate.recalculateTimeGap(
                    driving == null ? null : driving.getEstimatedLifespanDays());
        }
        candidates.saveAll(values);
        log.info("Sourcing time-gap recalculation completed: candidateCount={}", values.size());
        return values.size();
    }

    private static HeatCompositeDaily selectDriving(
            SourcingCandidate candidate, Map<Long, HeatCompositeDaily> latestByKeyword) {
        return candidate.getProduct().getKeywords().stream()
                .map(keyword -> latestByKeyword.get(keyword.getId()))
                .filter(Objects::nonNull)
                .filter(value -> value.getSlope7d() != null
                        && value.getSlope30d() != null
                        && value.getEstimatedLifespanDays() != null)
                .max(Comparator
                        .comparing(SourcingTimeGapRecalculationService::trendRaw)
                        .thenComparing(
                                value -> value.getKeyword().getId(),
                                Comparator.reverseOrder()))
                .orElse(null);
    }

    private static BigDecimal trendRaw(HeatCompositeDaily value) {
        return value.getSlope7d().multiply(SLOPE_7D_WEIGHT)
                .add(value.getSlope30d().multiply(SLOPE_30D_WEIGHT));
    }
}
