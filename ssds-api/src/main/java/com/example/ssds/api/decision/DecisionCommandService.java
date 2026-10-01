package com.example.ssds.api.decision;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import com.example.ssds.api.decision.dto.CampaignResultRequest;
import com.example.ssds.api.decision.dto.CloseDecisionRequest;
import com.example.ssds.api.decision.dto.CreateDecisionRequest;
import com.example.ssds.api.decision.dto.DecisionContextResponse;
import com.example.ssds.api.decision.dto.DecisionResponse;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.InsightType;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.AiInsight;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.CampaignResult;
import com.example.ssds.infra.entity.DecisionRecord;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.repository.AiInsightRepository;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.CampaignResultRepository;
import com.example.ssds.infra.repository.DecisionRecordRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 採購決策的寫入動作（規格書 §FR-11-1、§FR-11-2）。
 *
 * <p>每個寫入都留稽核紀錄（§FR-18），E2E 第 4 條與 FR-15 事後追查都靠它。
 */
@Service
@Transactional
public class DecisionCommandService {

    private static final Logger log = LoggerFactory.getLogger(DecisionCommandService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENTITY_TYPE = "DecisionRecord";

    private final ProductRepository productRepository;
    private final ProductScoreRepository productScoreRepository;
    private final AiInsightRepository aiInsightRepository;
    private final DecisionRecordRepository decisionRecordRepository;
    private final CampaignResultRepository campaignResultRepository;
    private final AppUserRepository appUserRepository;
    private final AuditLogRepository auditLogRepository;
    private final DecisionSnapshotFactory snapshotFactory;
    private final Clock clock;

    @Autowired
    public DecisionCommandService(
            ProductRepository productRepository,
            ProductScoreRepository productScoreRepository,
            AiInsightRepository aiInsightRepository,
            DecisionRecordRepository decisionRecordRepository,
            CampaignResultRepository campaignResultRepository,
            AppUserRepository appUserRepository,
            AuditLogRepository auditLogRepository,
            DecisionSnapshotFactory snapshotFactory) {
        this(productRepository, productScoreRepository, aiInsightRepository, decisionRecordRepository,
                campaignResultRepository, appUserRepository, auditLogRepository, snapshotFactory,
                Clock.system(DecisionMapper.BUSINESS_ZONE));
    }

    /** 測試用：注入固定時鐘，讓「今日」與逾期天數可重現。 */
    DecisionCommandService(
            ProductRepository productRepository,
            ProductScoreRepository productScoreRepository,
            AiInsightRepository aiInsightRepository,
            DecisionRecordRepository decisionRecordRepository,
            CampaignResultRepository campaignResultRepository,
            AppUserRepository appUserRepository,
            AuditLogRepository auditLogRepository,
            DecisionSnapshotFactory snapshotFactory,
            Clock clock) {
        this.productRepository = productRepository;
        this.productScoreRepository = productScoreRepository;
        this.aiInsightRepository = aiInsightRepository;
        this.decisionRecordRepository = decisionRecordRepository;
        this.campaignResultRepository = campaignResultRepository;
        this.appUserRepository = appUserRepository;
        this.auditLogRepository = auditLogRepository;
        this.snapshotFactory = snapshotFactory;
        this.clock = clock;
    }

    /**
     * FR-11-1 建立決策並自動產生快照。
     *
     * <ul>
     * <li>AC-11-1：綁定該品項最新的主情境現行評分；沒有就不可建立。</li>
     * <li>AC-11-7：followed_ai = (decision == AI 建議的 action)。</li>
     * <li>AC-11-2：未採納 AI 建議時理由必填。決策當下沒有 AI 建議時 followed_ai 無從比對而為 false，
     * 資料庫 ck_decision_reason（V2）要求 followed_ai = false 者必有理由，故此時理由同樣必填。</li>
     * </ul>
     */
    public DecisionResponse create(Long productId, CreateDecisionRequest request, String actorEmail, String ip) {
        AppUser actor = findActor(actorEmail);
        Product product = productRepository.findById(productId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的品項"));
        ProductScore score = productScoreRepository
                .findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(productId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_STATE_TRANSITION, "此品項尚無主情境評分，無法建立決策"));

        ProductStatus previousStatus = product.getStatus();
        ProductStatus nextStatus = nextProductStatus(product, request.decision());

        AiRecommendation ai = currentRecommendation(productId);
        boolean followedAi = ai.action() != null && ai.action() == request.decision();
        validateCreate(request, followedAi);

        DecisionRecord record = DecisionRecord.builder()
                .product(product)
                .score(score)
                .decision(request.decision())
                .aiInsight(ai.insight())
                .aiAction(ai.action())
                .followedAi(followedAi)
                .aiQtyMin(ai.qtyMin())
                .aiQtyMax(ai.qtyMax())
                // WATCH／REJECT 不開團：數量與上架日對它們沒有意義，不落地以免被誤讀為計畫
                .firstOrderQty(request.decision() == DecisionType.ADOPT ? request.firstOrderQty() : null)
                .expectedListDate(request.decision() == DecisionType.ADOPT ? request.expectedListDate() : null)
                .reason(blankToNull(request.reason()))
                .decidedBy(actor)
                .decidedAt(Instant.now(clock))
                .build();
        record.setSnapshot(snapshotFactory.create(record));
        DecisionRecord saved = decisionRecordRepository.saveAndFlush(record);

        // §7.4：決策與品項狀態在同一交易內轉換（ProductCommandService#changeStatus 的 TODO FR-11）。
        // 只改 status；product.reject_reason 屬 FR-03 的淘汰流程（至少 10 字），決策理由留在 decision_record.reason
        product.setStatus(nextStatus);
        productRepository.saveAndFlush(product);
        audit(actor, "UPDATE", "Product", productId,
                "{\"status\":\"" + previousStatus + "\"}",
                "{\"status\":\"" + nextStatus + "\",\"decisionId\":" + saved.getId() + "}",
                ip);

        audit(actor, "CREATE", ENTITY_TYPE, saved.getId(), null,
                "{\"decision\":\"" + saved.getDecision() + "\",\"scoreId\":" + score.getId()
                        + ",\"followedAi\":" + followedAi + "}",
                ip);
        return DecisionMapper.toResponse(saved, today());
    }

    /**
     * 建立決策前的判斷依據（GET /products/{id}/decision-context，規格補充）。
     * 與 {@link #create} 共用同一套評分查詢、AI 解析與狀態機，表單看到的就是建立時會套用的規則。
     */
    @Transactional(readOnly = true)
    public DecisionContextResponse context(Long productId) {
        Product product = productRepository.findById(productId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的品項"));
        DecisionContextResponse.ScoreSummary score = productScoreRepository
                .findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(productId)
                .map(s -> new DecisionContextResponse.ScoreSummary(
                        s.getId(), s.getPeriod(), s.getSceneType(), s.getFinalScore(), s.getGrade()))
                .orElse(null);
        AiRecommendation ai = currentRecommendation(productId);

        List<DecisionType> allowed = new ArrayList<>();
        String blockedReason = null;
        if (score == null) {
            blockedReason = "此品項尚無主情境評分，無法建立決策";
        } else {
            for (DecisionType type : DecisionType.values()) {
                try {
                    nextProductStatus(product, type);
                    allowed.add(type);
                } catch (BusinessException e) {
                    blockedReason = e.getMessage();
                }
            }
            if (!allowed.isEmpty()) {
                blockedReason = null;
            }
        }
        return new DecisionContextResponse(
                product.getId(),
                product.getName(),
                product.getStatus(),
                product.getTrackType(),
                score,
                ai.action() == null ? null : new DecisionContextResponse.AiSuggestion(
                        ai.action(), ai.qtyMin(), ai.qtyMax(), ai.quantityText(), ai.reasoning()),
                allowed,
                blockedReason);
    }

    /**
     * §7.4 建立決策觸發的品項狀態轉換。表外的轉換一律 409：
     * EVALUATING 可轉三種決策；WATCHING 只能再轉 ADOPT／REJECT；
     * 已採納、已上架、已淘汰、草稿都不可再建立決策。B 軌品項狀態固定為 EVALUATING，須先成案轉 A 軌。
     */
    static ProductStatus nextProductStatus(Product product, DecisionType decision) {
        if (product.getTrackType() == TrackType.B) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "B 軌品項須先成案轉入 A 軌，才能建立採購決策");
        }
        ProductStatus target = switch (decision) {
            case ADOPT -> ProductStatus.ADOPTED;
            case WATCH -> ProductStatus.WATCHING;
            case REJECT -> ProductStatus.REJECTED;
        };
        ProductStatus source = product.getStatus();
        boolean allowed = source == ProductStatus.EVALUATING
                || (source == ProductStatus.WATCHING && target != ProductStatus.WATCHING);
        if (!allowed) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "品項目前狀態為 " + source + "，不可建立 " + decision + " 決策");
        }
        return target;
    }

    /**
     * FR-11-2 標記結案。僅 ADOPT 可結案（AC-11-8），且品項須已上架（§7.4 LISTED 才有「標記結案」事件）；
     * 已回填者不可再改結案日。
     * 已結案未回填時允許更正結案日（設計決定：打錯日期會連帶算錯逾期天數，必須能改），
     * 更正前後的值都留在稽核紀錄。
     */
    public DecisionResponse close(Long decisionId, CloseDecisionRequest request, String actorEmail, String ip) {
        AppUser actor = findActor(actorEmail);
        DecisionRecord record = findDecision(decisionId);
        requireAdopt(record);
        if (record.getResult() != null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "已回填結果的決策不可再變更結案日");
        }
        if (record.getProduct().getStatus() != ProductStatus.LISTED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "品項尚未標記上架（開團），不可結案");
        }
        LocalDate today = today();
        LocalDate endDate = request == null || request.campaignEndDate() == null
                ? today
                : request.campaignEndDate();
        // §FR-11-2「預設今日，可回填過去日期」：允許範圍為今日及以前
        if (endDate.isAfter(today)) {
            throw validation("campaignEndDate", "結案日期不可晚於今日");
        }
        LocalDate before = record.getCampaignEndDate();
        record.setCampaignEndDate(endDate);
        decisionRecordRepository.saveAndFlush(record);

        audit(actor, "CLOSE", ENTITY_TYPE, decisionId,
                before == null ? null : "{\"campaignEndDate\":\"" + before + "\"}",
                "{\"campaignEndDate\":\"" + endDate + "\"}",
                ip);
        return DecisionMapper.toResponse(record, today);
    }

    /** FR-11-2 結案回填。須為已結案的 ADOPT，且只能回填一次。 */
    public DecisionResponse fillResult(
            Long decisionId, CampaignResultRequest request, String actorEmail, String ip) {
        AppUser actor = findActor(actorEmail);
        DecisionRecord record = findDecision(decisionId);
        requireAdopt(record);
        if (record.getCampaignEndDate() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "請先標記結案再回填結果");
        }
        if (record.getResult() != null) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "此決策已回填結果，不可重複回填");
        }
        CampaignResult result = CampaignResult.builder()
                .decision(record)
                .actualQty(request.actualQty())
                .selloutStatus(request.selloutStatus())
                .returnRate(request.returnRate())
                .realizedMarginRate(request.realizedMarginRate())
                .postNoteCode(request.postNoteCode())
                .postNoteText(blankToNull(request.postNoteText()))
                .filledBy(actor)
                .filledAt(Instant.now(clock))
                .build();
        // 子表（@MapsId）由自己的 repository 直接寫入，不經父表 decision_record 的 cascade
        record.setResult(campaignResultRepository.saveAndFlush(result));

        audit(actor, "FILL_RESULT", ENTITY_TYPE, decisionId, null,
                "{\"actualQty\":" + request.actualQty() + ",\"selloutStatus\":\""
                        + request.selloutStatus() + "\"}",
                ip);
        return DecisionMapper.toResponse(record, today());
    }

    /** §8.2 POST /decisions/{id}/review 覆核（§2.1 第 13 列）。已覆核者不可重複覆核。 */
    public DecisionResponse review(Long decisionId, String actorEmail, String ip) {
        AppUser actor = findActor(actorEmail);
        DecisionRecord record = findDecision(decisionId);
        if (record.getReviewedBy() != null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "此決策已覆核");
        }
        record.setReviewedBy(actor);
        record.setReviewedAt(Instant.now(clock));
        decisionRecordRepository.saveAndFlush(record);

        audit(actor, "REVIEW", ENTITY_TYPE, decisionId, null, "{\"reviewed\":true}", ip);
        return DecisionMapper.toResponse(record, today());
    }

    private void validateCreate(CreateDecisionRequest request, boolean followedAi) {
        if (request.decision() == DecisionType.ADOPT && request.firstOrderQty() == null) {
            throw validation("firstOrderQty", "採納決策須填寫建議首批數量");
        }
        if (!followedAi && blankToNull(request.reason()) == null) {
            throw validation("reason", "未採納 AI 建議時須填寫理由");
        }
    }

    /**
     * 讀取品項目前的 AI 進貨建議（§6.3 Agent 4 輸出，存於 ai_insight RECOMMENDATION）。
     * 內容無法解析時視同沒有建議並記錄警告，不擋住決策——決策是人的動作，
     * 不能因為 AI 輸出壞掉而做不了。
     */
    private AiRecommendation currentRecommendation(Long productId) {
        return aiInsightRepository
                .findByProductIdAndInsightTypeAndCurrentTrue(productId, InsightType.RECOMMENDATION)
                .map(DecisionCommandService::parse)
                .orElse(AiRecommendation.NONE);
    }

    static AiRecommendation parse(AiInsight insight) {
        try {
            JsonNode node = JSON.readTree(insight.getContentJson());
            DecisionType action = DecisionType.valueOf(node.path("action").asText());
            Integer qtyMin = node.hasNonNull("qtyMin") ? node.get("qtyMin").asInt() : null;
            Integer qtyMax = node.hasNonNull("qtyMax") ? node.get("qtyMax").asInt() : null;
            if (qtyMin != null && qtyMax != null && qtyMin > qtyMax) {
                // 違反 ck_decision_ai_qty_range：數量區間作廢，動作仍可用
                qtyMin = null;
                qtyMax = null;
            }
            return new AiRecommendation(insight, action, qtyMin, qtyMax,
                    node.hasNonNull("quantityText") ? node.get("quantityText").asText() : null,
                    node.hasNonNull("reasoning") ? node.get("reasoning").asText() : null);
        } catch (Exception e) {
            log.warn("ai_insight {} 的 RECOMMENDATION 內容無法解析，視同無 AI 建議", insight.getId(), e);
            return AiRecommendation.NONE;
        }
    }

    record AiRecommendation(AiInsight insight, DecisionType action, Integer qtyMin, Integer qtyMax,
            String quantityText, String reasoning) {
        static final AiRecommendation NONE = new AiRecommendation(null, null, null, null, null, null);
    }

    private void requireAdopt(DecisionRecord record) {
        if (record.getDecision() != DecisionType.ADOPT) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "僅採納（ADOPT）的決策有開團，WATCH／REJECT 無結案與回填");
        }
    }

    private DecisionRecord findDecision(Long decisionId) {
        return decisionRecordRepository.findById(decisionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的決策"));
    }

    private AppUser findActor(String actorEmail) {
        if (actorEmail == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return appUserRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "登入使用者不存在或已失效"));
    }

    private void audit(AppUser actor, String action, String entityType, Long entityId,
            String before, String after, String ip) {
        auditLogRepository.save(AuditLog.builder()
                .user(actor)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .beforeJson(before)
                .afterJson(after)
                .ip(ip)
                .build());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static BusinessException validation(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message, List.of(new FieldError(field, message)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
