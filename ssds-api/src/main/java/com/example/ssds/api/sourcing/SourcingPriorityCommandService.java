package com.example.ssds.api.sourcing;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.sourcing.dto.SourcingPriorityActionResponse;
import com.example.ssds.api.sourcing.dto.SourcingPriorityCapabilities;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 使用者明確操作的尋源生命週期命令；不得由一般品項更新 API 取代。 */
@Service
public class SourcingPriorityCommandService {
    private final SourcingCandidateRepository candidates;
    private final HeatCompositeDailyRepository composites;
    private final AuditLogRepository audits;
    private final AppUserRepository users;

    public SourcingPriorityCommandService(
            SourcingCandidateRepository candidates,
            HeatCompositeDailyRepository composites,
            AuditLogRepository audits,
            AppUserRepository users) {
        this.candidates = candidates;
        this.composites = composites;
        this.audits = audits;
        this.users = users;
    }

    @Transactional
    public SourcingPriorityActionResponse watch(Long productId, String actorEmail) {
        SourcingCandidate candidate = load(productId);
        return watch(candidate, actorEmail);
    }

    @Transactional
    public SourcingPriorityActionResponse watch(
            SourcingCandidate candidate, String actorEmail) {
        requireBTrack(candidate);
        if (candidate.getProduct().getSourcingStatus() == SourcingStatus.PROMOTED) {
            throw invalidState("已轉為 A 軌的品項不可存為觀察");
        }
        SourcingStatus previousStatus = candidate.getProduct().getSourcingStatus();
        candidate.watch();
        candidates.save(candidate);
        if (previousStatus == SourcingStatus.REJECTED) {
            audits.save(AuditLog.builder()
                    .user(actorEmail == null ? null : users.findByEmail(actorEmail).orElse(null))
                    .action("SOURCING_CANDIDATE_REVIVED")
                    .entityType("SOURCING_CANDIDATE")
                    .entityId(candidate.getId())
                    .beforeJson("{\"sourcingStatus\":\"REJECTED\"}")
                    .afterJson("{\"sourcingStatus\":\"PENDING\",\"productStatus\":\"WATCHING\"}")
                    .build());
        }
        return SourcingPriorityActionResponse.from(candidate);
    }

    @Transactional
    public SourcingPriorityActionResponse prioritize(Long productId) {
        SourcingCandidate candidate = load(productId);
        requireBTrack(candidate);
        SourcingStatus status = candidate.getProduct().getSourcingStatus();
        if (status == SourcingStatus.REJECTED || status == SourcingStatus.PROMOTED) {
            throw invalidState("已淘汰或已轉軌的品項不可加入尋源優先序");
        }
        HeatCompositeDaily latest = latestComposite(candidate);
        if (latest == null || latest.getEstimatedLifespanDays() == null) {
            throw invalidState("尚無時效落差資料，無法加入尋源優先序");
        }
        Integer liveGap = latest.getEstimatedLifespanDays() - candidate.getLeadTimeDays();
        if (!Objects.equals(liveGap, candidate.getTimeGapDays())) {
            throw invalidState("時效落差不是目前最新合成結果，請等待資料更新後再試");
        }
        if (liveGap < 0) {
            throw invalidState("時效落差為負，依規格不可加入尋源優先序");
        }
        candidate.prioritize();
        candidates.save(candidate);
        return SourcingPriorityActionResponse.from(candidate);
    }

    @Transactional(readOnly = true)
    public SourcingPriorityCapabilities capabilities(
            SourcingCandidate candidate, HeatCompositeDaily latest) {
        boolean canWatch = candidate.getProduct().getTrackType() == TrackType.B
                && candidate.getProduct().getSourcingStatus() != SourcingStatus.PROMOTED;
        SourcingStatus status = candidate.getProduct().getSourcingStatus();
        if (status == SourcingStatus.REJECTED) {
            return new SourcingPriorityCapabilities(canWatch, false,
                    "已淘汰品項須先存為觀察");
        }
        if (status == SourcingStatus.PROMOTED) {
            return new SourcingPriorityCapabilities(false, false,
                    "已轉為 A 軌的品項不可加入尋源優先序");
        }
        if (latest == null || latest.getEstimatedLifespanDays() == null) {
            return new SourcingPriorityCapabilities(canWatch, false,
                    "尚無時效落差資料，請等待每日熱度合成");
        }
        Integer liveGap = latest.getEstimatedLifespanDays() - candidate.getLeadTimeDays();
        if (!Objects.equals(liveGap, candidate.getTimeGapDays())) {
            return new SourcingPriorityCapabilities(canWatch, false,
                    "時效落差不是目前最新合成結果，請等待資料更新後再試");
        }
        if (liveGap < 0) {
            return new SourcingPriorityCapabilities(canWatch, false,
                    "時效落差為負，依規格不可加入尋源優先序");
        }
        return new SourcingPriorityCapabilities(canWatch, true, null);
    }

    private SourcingCandidate load(Long productId) {
        return candidates.findDetailedByProductId(productId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的尋源候選"));
    }

    private static void requireBTrack(SourcingCandidate candidate) {
        if (candidate.getProduct().getTrackType() != TrackType.B) {
            throw invalidState("只有 B 軌品項可執行尋源優先序操作");
        }
    }

    public HeatCompositeDaily latestComposite(SourcingCandidate candidate) {
        if (candidate.getDrivingKeyword() == null || !candidate.getDrivingKeyword().isEnabled()) {
            return null;
        }
        return composites
                .findFirstByKeywordIdOrderByStatDateDesc(candidate.getDrivingKeyword().getId())
                .orElse(null);
    }

    private static BusinessException invalidState(String message) {
        return new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, message);
    }
}
