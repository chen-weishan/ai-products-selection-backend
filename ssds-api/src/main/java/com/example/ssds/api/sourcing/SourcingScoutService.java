package com.example.ssds.api.sourcing;

import com.example.ssds.ai.agent.SourcingScoutAgent;
import com.example.ssds.ai.model.sourcing.*;
import com.example.ssds.ai.prompt.PromptSanitizer;
import com.example.ssds.ai.prompt.sourcing.SourcingKeywordNormalizer;
import com.example.ssds.api.aitask.service.AiTaskService;
import com.example.ssds.api.aitask.dto.AiTaskResponse;
import com.example.ssds.api.common.error.*;
import com.example.ssds.api.sourcing.dto.*;
import com.example.ssds.core.domain.*;
import com.example.ssds.infra.entity.*;
import com.example.ssds.infra.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import org.slf4j.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourcingScoutService {
    private static final Logger log = LoggerFactory.getLogger(SourcingScoutService.class);
    private final CategoryRepository categories; private final CategoryLeadTimeRepository leadTimes;
    private final TrendKeywordRepository keywords; private final ProductRepository products;
    private final SourcingCandidateRepository candidates;
    private final AiTaskItemRepository taskItems;
    private final SourcingPriorityCommandService priorityCommands;
    private final AiTaskService tasks; private final PromptSanitizer sanitizer;
    private final SourcingScoutAgent agent; private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;

    public SourcingScoutService(CategoryRepository categories, CategoryLeadTimeRepository leadTimes,
            TrendKeywordRepository keywords, ProductRepository products, SourcingCandidateRepository candidates,
            AiTaskItemRepository taskItems,
            SourcingPriorityCommandService priorityCommands,
            AiTaskService tasks, PromptSanitizer sanitizer,
            SourcingScoutAgent agent, ObjectMapper mapper,
            ApplicationEventPublisher events) {
        this.categories=categories; this.leadTimes=leadTimes; this.keywords=keywords; this.products=products;
        this.candidates=candidates; this.taskItems=taskItems;
        this.priorityCommands=priorityCommands;
        this.tasks=tasks; this.sanitizer=sanitizer;
        this.agent=agent; this.mapper=mapper; this.events=events;
    }

    @Transactional
    public AiTaskResponse start(SourcingScoutRequest request) {
        Category category = categories.findById(request.categoryId()).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定品類"));
        leadTimes.findById(category.getId()).orElseThrow(() ->
                new BusinessException(ErrorCode.VALIDATION_FAILED, "此品類尚未設定尋源前置天數"));
        String normalized = SourcingKeywordNormalizer.normalize(request.keyword());
        if (normalized.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "尋源關鍵字不得為空白");
        }
        Product product = keywords.findByKeyword(normalized)
                .flatMap(keyword -> products.findReusableSourcingProduct(
                        keyword.getId(), category.getId()))
                .orElse(null);
        return tasks.createSourcingScout(normalized, category, product, request.forceRefresh());
    }

    @Transactional
    public SourcingScoutResponse scout(Long productId, boolean forceRefresh) {
        SourcingCandidate candidate = load(productId);
        SourcingScoutInput input = sanitizer.sanitizeSourcingScout(new SourcingScoutInput(
                candidate.getKeyword().getKeyword(), candidate.getCategory().getId(), candidate.getCategory().getName()));
        SourcingScoutResult result = agent.scout(input, forceRefresh);
        Instant now = Instant.now();
        apply(candidate, result, now);
        candidates.save(candidate);
        log.info("SourcingScout completed: productId={}, promptVersion={}, modelAlias=MODEL_REASONING, cacheHit={}",
                productId, result.promptVersion(), result.cacheHit());
        return response(candidate, result.cacheHit());
    }
    @Transactional(readOnly=true) public SourcingScoutResponse latest(Long productId) {
        return response(load(productId), false);
    }

    @Transactional
    public SourcingScoutResponse scout(AiTaskItem item, boolean forceRefresh) {
        if (item.getScoutKeyword() == null || item.getScoutCategory() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "探索任務缺少原始字詞或品類");
        }
        SourcingScoutInput input = sanitizer.sanitizeSourcingScout(new SourcingScoutInput(
                item.getScoutKeyword(), item.getScoutCategory().getId(), item.getScoutCategory().getName()));
        SourcingScoutResult result = agent.scout(input, forceRefresh);
        Instant now = Instant.now();
        item.setScoutReport(result.output().report());
        item.setScoutOpportunitySignals(write(result.output().opportunitySignals()));
        item.setScoutRiskSignals(write(result.output().riskSignals()));
        item.setScoutModel(result.model());
        item.setScoutPromptVersion(result.promptVersion());
        item.setScoutReportGeneratedAt(now);

        Optional<SourcingCandidate> candidate = item.getProduct() == null
                ? Optional.empty()
                : candidates.findDetailedByProductId(item.getProduct().getId());
        candidate.ifPresent(value -> {
            apply(value, result, now);
            candidates.save(value);
        });
        return candidate.map(value -> response(value, item.getId(), result.cacheHit()))
                .orElseGet(() -> SourcingScoutResponse.from(item, mapper, result.cacheHit()));
    }

    @Transactional(readOnly = true)
    public SourcingScoutResponse latestResult(Long itemId) {
        AiTaskItem item = taskItems.findSourcingResultById(itemId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的探索結果"));
        if (item.getTask().getTaskType() != AiTaskType.SOURCING_SCOUT
                || item.getScoutReport() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "探索結果尚未完成");
        }
        if (item.getProduct() != null) {
            Optional<SourcingCandidate> candidate = candidates.findDetailedByProductId(item.getProduct().getId());
            if (candidate.isPresent()) return response(candidate.get(), item.getId(), false);
        }
        return SourcingScoutResponse.from(item, mapper, false);
    }

    @Transactional
    public SourcingScoutResponse watchResult(Long itemId, String actorEmail) {
        AiTaskItem item = taskItems.findSourcingResultByIdForUpdate(itemId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的探索結果"));
        requireCompletedScoutResult(item);
        Category category = item.getScoutCategory();
        CategoryLeadTime leadTime = leadTimes.findById(category.getId()).orElseThrow(() ->
                new BusinessException(ErrorCode.VALIDATION_FAILED, "此品類尚未設定尋源前置天數"));

        Optional<TrendKeyword> existingKeyword = keywords.findByKeyword(item.getScoutKeyword());
        TrendKeyword keyword = existingKeyword.orElseGet(() -> keywords.save(TrendKeyword.builder()
                .keyword(item.getScoutKeyword()).enabled(true).build()));

        Product product = item.getProduct();
        if (product == null) {
            product = products.findReusableSourcingProduct(keyword.getId(), category.getId())
                    .orElseGet(() -> products.save(Product.builder()
                            .name(item.getScoutKeyword())
                            .category(category)
                            .trackType(TrackType.B)
                            .status(ProductStatus.DRAFT)
                            .sourcingStatus(SourcingStatus.PENDING)
                            .keywords(new LinkedHashSet<>(java.util.Set.of(keyword)))
                            .build()));
        }
        if (product.getTrackType() != TrackType.B
                || product.getSourcingStatus() == SourcingStatus.PROMOTED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "探索結果目前不可存為觀察");
        }
        product.getKeywords().add(keyword);
        Product targetProduct = product;

        SourcingCandidate candidate = candidates.findDetailedByProductId(product.getId())
                .orElseGet(() -> SourcingCandidate.builder()
                        .product(targetProduct)
                        .keyword(keyword)
                        .category(category)
                        .leadTimeDays(leadTime.getLeadTimeDays())
                        .build());
        copyScoutResult(item, candidate);
        priorityCommands.watch(candidate, actorEmail);
        item.setProduct(product);
        item.setKeyword(keyword);
        taskItems.save(item);
        if (existingKeyword.isEmpty()) {
            events.publishEvent(new SourcingKeywordObservedEvent(keyword.getId()));
        }
        return response(candidate, item.getId(), false);
    }

    @Transactional
    public SourcingScoutResponse prioritizeResult(Long itemId) {
        AiTaskItem item = taskItems.findSourcingResultById(itemId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的探索結果"));
        requireCompletedScoutResult(item);
        if (item.getProduct() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "陌生字詞尚未存為觀察，無法加入尋源優先序");
        }
        priorityCommands.prioritize(item.getProduct().getId());
        SourcingCandidate candidate = candidates.findDetailedByProductId(item.getProduct().getId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的尋源候選"));
        return response(candidate, item.getId(), false);
    }

    private static void requireCompletedScoutResult(AiTaskItem item) {
        if (item.getTask().getTaskType() != AiTaskType.SOURCING_SCOUT
                || item.getScoutReport() == null
                || item.getScoutKeyword() == null
                || item.getScoutCategory() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "探索結果尚未完成或缺少必要資料");
        }
    }

    private static void copyScoutResult(AiTaskItem item, SourcingCandidate candidate) {
        candidate.setScoutReport(item.getScoutReport());
        candidate.setOpportunitySignals(item.getScoutOpportunitySignals());
        candidate.setRiskSignals(item.getScoutRiskSignals());
        candidate.setModel(item.getScoutModel());
        candidate.setPromptVersion(item.getScoutPromptVersion());
        candidate.setReportGeneratedAt(item.getScoutReportGeneratedAt());
        candidate.setScoutedAt(item.getScoutReportGeneratedAt());
    }

    private SourcingCandidate load(Long id) { return candidates.findDetailedByProductId(id).orElseThrow(() ->
            new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的尋源候選")); }
    private String write(Object value) { try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("無法序列化尋源訊號", e); } }
    private SourcingScoutResponse response(SourcingCandidate candidate, boolean cacheHit) {
        HeatCompositeDaily composite = priorityCommands.latestComposite(candidate);
        return SourcingScoutResponse.from(
                candidate, composite, mapper, cacheHit,
                priorityCommands.capabilities(candidate, composite));
    }

    private SourcingScoutResponse response(
            SourcingCandidate candidate, Long itemId, boolean cacheHit) {
        HeatCompositeDaily composite = priorityCommands.latestComposite(candidate);
        return SourcingScoutResponse.from(
                candidate, itemId, composite, mapper, cacheHit,
                priorityCommands.capabilities(candidate, composite));
    }

    private void apply(SourcingCandidate candidate, SourcingScoutResult result, Instant now) {
        candidate.setScoutReport(result.output().report());
        candidate.setOpportunitySignals(write(result.output().opportunitySignals()));
        candidate.setRiskSignals(write(result.output().riskSignals()));
        candidate.setModel(result.model());
        candidate.setPromptVersion(result.promptVersion());
        candidate.setReportGeneratedAt(now);
        candidate.setScoutedAt(now);
    }
}
