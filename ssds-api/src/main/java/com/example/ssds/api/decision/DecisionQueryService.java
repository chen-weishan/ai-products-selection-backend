package com.example.ssds.api.decision;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.decision.dto.DecisionResponse;
import com.example.ssds.api.decision.dto.DecisionSnapshotResponse;
import com.example.ssds.api.score.DrivingTargetLookup;
import com.example.ssds.api.score.ScoreMapper;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.CampaignResult;
import com.example.ssds.infra.entity.CampaignSnapshot;
import com.example.ssds.infra.entity.DecisionRecord;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.repository.DecisionRecordRepository;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;
import com.example.ssds.infra.repository.ScoreFactorRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Subquery;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 採購決策的查詢（規格書 §8.2 GET /decisions、/decisions/{id}、/decisions/{id}/snapshot）。 */
@Service
@Transactional(readOnly = true)
public class DecisionQueryService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> AVAILABILITY_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, BigDecimal>> WEIGHTS_TYPE = new TypeReference<>() { };

    private final DecisionRecordRepository decisionRecordRepository;
    private final ScoreFactorRepository scoreFactorRepository;
    private final SceneClassificationLogRepository sceneClassificationLogRepository;
    private final DrivingTargetLookup drivingTargetLookup;
    private final Clock clock;

    @Autowired
    public DecisionQueryService(
            DecisionRecordRepository decisionRecordRepository,
            ScoreFactorRepository scoreFactorRepository,
            SceneClassificationLogRepository sceneClassificationLogRepository,
            DrivingTargetLookup drivingTargetLookup) {
        this(decisionRecordRepository, scoreFactorRepository, sceneClassificationLogRepository,
                drivingTargetLookup, Clock.system(DecisionMapper.BUSINESS_ZONE));
    }

    DecisionQueryService(
            DecisionRecordRepository decisionRecordRepository,
            ScoreFactorRepository scoreFactorRepository,
            SceneClassificationLogRepository sceneClassificationLogRepository,
            DrivingTargetLookup drivingTargetLookup,
            Clock clock) {
        this.decisionRecordRepository = decisionRecordRepository;
        this.scoreFactorRepository = scoreFactorRepository;
        this.sceneClassificationLogRepository = sceneClassificationLogRepository;
        this.drivingTargetLookup = drivingTargetLookup;
        this.clock = clock;
    }

    /**
     * 決策清單。{@code from}／{@code to} 以決策日（Asia/Taipei）篩選，兩端皆含。
     * {@code pendingResult = true} 只回「已結案、未回填」的 ADOPT（§FR-11-2 待回填）。
     * {@code productId} 為規格外的補充參數，供品項詳情頁列出該品項的決策歷程。
     */
    public PageResponse<DecisionResponse> list(DecisionSearchCriteria criteria, Pageable pageable) {
        LocalDate today = LocalDate.now(clock);
        return PageResponse.from(decisionRecordRepository
                .findAll(toSpecification(criteria), pageable)
                .map(record -> DecisionMapper.toResponse(record, today)));
    }

    public DecisionResponse get(Long decisionId) {
        return DecisionMapper.toResponse(findDecision(decisionId), LocalDate.now(clock));
    }

    /**
     * 決策快照：分數與因子 join 回 product_score／score_factor，其餘取自 campaign_snapshot。
     * 情境判定取「快照建立當下」該 period 的最新判定，之後的覆寫不回頭影響（AC-11-6）。
     */
    public DecisionSnapshotResponse snapshot(Long decisionId) {
        DecisionRecord record = findDecision(decisionId);
        ProductScore score = record.getScore();
        CampaignSnapshot snapshot = record.getSnapshot();
        Instant asOf = snapshot != null && snapshot.getCreatedAt() != null
                ? snapshot.getCreatedAt()
                : record.getDecidedAt();
        SceneClassificationLog scene = sceneClassificationLogRepository
                .findByPeriodAndProductIdInOrderByCreatedAtDesc(score.getPeriod(), List.of(record.getProduct().getId()))
                .stream()
                .filter(log -> log.getCreatedAt() == null || !log.getCreatedAt().isAfter(asOf))
                .findFirst()
                .orElse(null);
        boolean overridden = snapshot != null && snapshot.isSceneOverridden();
        List<ScoreFactor> factors = scoreFactorRepository.findByScoreId(score.getId());

        return new DecisionSnapshotResponse(
                record.getId(),
                ScoreMapper.toDetail(score, factors, drivingTargetLookup.resolve(factors)),
                score.getWeightVersion() == null ? null : score.getWeightVersion().getId(),
                score.getWeightVersion() == null ? null : score.getWeightVersion().getVersionNo(),
                scene == null ? null : scene.getAiSceneType(),
                scene == null ? null : scene.getAiConfidence(),
                overridden,
                overridden && scene != null && scene.getOverriddenBy() != null
                        ? scene.getOverriddenBy().getDisplayName() : null,
                overridden && scene != null ? scene.getOverrideReason() : null,
                record.getDecision(),
                record.getAiAction(),
                record.isFollowedAi(),
                record.getAiQtyMin(),
                record.getAiQtyMax(),
                snapshot == null ? null : readMap(snapshot.getSourceAvailability(), AVAILABILITY_TYPE),
                snapshot == null ? null : readMap(snapshot.getAppliedCompositeWeights(), WEIGHTS_TYPE),
                snapshot == null ? null : readThresholds(snapshot.getAppliedThresholds()),
                record.getDecidedBy().getDisplayName(),
                DecisionMapper.toDisplayTime(record.getDecidedAt()),
                snapshot == null ? null : DecisionMapper.toDisplayTime(snapshot.getCreatedAt()));
    }

    static Specification<DecisionRecord> toSpecification(DecisionSearchCriteria criteria) {
        Specification<DecisionRecord> spec = (root, query, cb) -> cb.isNull(root.get("product").get("deletedAt"));
        if (criteria.decidedBy() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("decidedBy").get("id"), criteria.decidedBy()));
        }
        if (criteria.productId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("product").get("id"), criteria.productId()));
        }
        if (criteria.categoryId() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.join("product").join("category", JoinType.LEFT).get("id"), criteria.categoryId()));
        }
        if (criteria.decision() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("decision"), criteria.decision()));
        }
        if (criteria.from() != null) {
            Instant fromInstant = criteria.from().atStartOfDay(DecisionMapper.BUSINESS_ZONE).toInstant();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("decidedAt"), fromInstant));
        }
        if (criteria.to() != null) {
            Instant toExclusive = criteria.to().plusDays(1).atStartOfDay(DecisionMapper.BUSINESS_ZONE).toInstant();
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("decidedAt"), toExclusive));
        }
        if (Boolean.TRUE.equals(criteria.pendingResult())) {
            spec = spec.and((root, query, cb) -> {
                Subquery<Long> filled = query.subquery(Long.class);
                var result = filled.from(CampaignResult.class);
                filled.select(result.get("decisionId")).where(cb.equal(result.get("decisionId"), root.get("id")));
                return cb.and(
                        cb.equal(root.get("decision"), DecisionType.ADOPT),
                        cb.isNotNull(root.get("campaignEndDate")),
                        cb.not(cb.exists(filled)));
            });
        }
        return spec;
    }

    DecisionRecord findDecision(Long decisionId) {
        return decisionRecordRepository.findById(decisionId)
                .filter(record -> record.getProduct().getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的決策"));
    }

    /** 快照 JSON 讀不出來時回 null，不讓一筆舊資料弄壞整頁。 */
    private static <T> T readMap(String json, TypeReference<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    private static DecisionSnapshotResponse.AppliedThresholds readThresholds(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(json);
            return new DecisionSnapshotResponse.AppliedThresholds(
                    node.hasNonNull("sceneType")
                            ? SceneType.valueOf(node.get("sceneType").asText())
                            : null,
                    node.hasNonNull("gradeAMin") ? node.get("gradeAMin").decimalValue() : null,
                    node.hasNonNull("gradeBMin") ? node.get("gradeBMin").decimalValue() : null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 清單與準確度共用的篩選條件。 */
    public record DecisionSearchCriteria(
            LocalDate from,
            LocalDate to,
            Long decidedBy,
            Long categoryId,
            Long productId,
            DecisionType decision,
            Boolean pendingResult) {
    }
}
