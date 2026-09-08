package com.example.ssds.api.sourcing;

import com.example.ssds.ai.agent.SourcingScoutAgent;
import com.example.ssds.ai.model.*;
import com.example.ssds.ai.prompt.*;
import com.example.ssds.api.aitask.AiTaskService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourcingScoutService {
    private static final Logger log = LoggerFactory.getLogger(SourcingScoutService.class);
    private final CategoryRepository categories; private final CategoryLeadTimeRepository leadTimes;
    private final TrendKeywordRepository keywords; private final ProductRepository products;
    private final SourcingCandidateRepository candidates;
    private final HeatCompositeDailyRepository heatComposites;
    private final AiTaskService tasks; private final PromptSanitizer sanitizer;
    private final SourcingScoutAgent agent; private final ObjectMapper mapper;

    public SourcingScoutService(CategoryRepository categories, CategoryLeadTimeRepository leadTimes,
            TrendKeywordRepository keywords, ProductRepository products, SourcingCandidateRepository candidates,
            HeatCompositeDailyRepository heatComposites,
            AiTaskService tasks, PromptSanitizer sanitizer,
            SourcingScoutAgent agent, ObjectMapper mapper) {
        this.categories=categories; this.leadTimes=leadTimes; this.keywords=keywords; this.products=products;
        this.candidates=candidates; this.heatComposites=heatComposites;
        this.tasks=tasks; this.sanitizer=sanitizer;
        this.agent=agent; this.mapper=mapper;
    }

    @Transactional
    public AiTaskResponse start(SourcingScoutRequest request) {
        Category category = categories.findById(request.categoryId()).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定品類"));
        CategoryLeadTime leadTime = leadTimes.findById(category.getId()).orElseThrow(() ->
                new BusinessException(ErrorCode.VALIDATION_FAILED, "此品類尚未設定尋源前置天數"));
        String normalized = request.keyword().strip();
        TrendKeyword keyword = keywords.findByKeyword(normalized).orElseGet(() -> keywords.save(
                TrendKeyword.builder().keyword(normalized).enabled(true).build()));
        Product product = products.findReusableSourcingProduct(keyword.getId(), category.getId())
                .orElseGet(() -> createProduct(normalized, category, keyword));
        SourcingCandidate candidate = candidates.findByProductId(product.getId())
                .orElseGet(() -> createCandidate(product, category, leadTime, keyword));
        return tasks.createSourcingScout(candidate.getProduct(), request.forceRefresh());
    }

    private Product createProduct(String name, Category category, TrendKeyword keyword) {
        return products.save(Product.builder().name(name).category(category).trackType(TrackType.B)
                .status(ProductStatus.DRAFT).sourcingStatus(SourcingStatus.PENDING)
                .keywords(new LinkedHashSet<>(java.util.Set.of(keyword))).build());
    }

    private SourcingCandidate createCandidate(
            Product product, Category category, CategoryLeadTime leadTime, TrendKeyword keyword) {
        return candidates.save(SourcingCandidate.builder().product(product).keyword(keyword).category(category)
                .leadTimeDays(leadTime.getLeadTimeDays()).build());
    }

    @Transactional
    public SourcingScoutResponse scout(Long productId, boolean forceRefresh) {
        SourcingCandidate candidate = load(productId);
        SourcingScoutInput input = sanitizer.sanitizeSourcingScout(new SourcingScoutInput(
                candidate.getKeyword().getKeyword(), candidate.getCategory().getId(), candidate.getCategory().getName()));
        SourcingScoutResult result = agent.scout(input, forceRefresh);
        Instant now = Instant.now();
        candidate.setScoutReport(result.output().report());
        candidate.setOpportunitySignals(write(result.output().opportunitySignals()));
        candidate.setRiskSignals(write(result.output().riskSignals()));
        candidate.setModel(result.model()); candidate.setPromptVersion(result.promptVersion());
        candidate.setReportGeneratedAt(now); candidate.setScoutedAt(now);
        candidates.save(candidate);
        log.info("SourcingScout completed: productId={}, promptVersion={}, modelAlias=MODEL_REASONING, cacheHit={}",
                productId, result.promptVersion(), result.cacheHit());
        return response(candidate);
    }
    @Transactional(readOnly=true) public SourcingScoutResponse latest(Long productId) { return response(load(productId)); }
    private SourcingCandidate load(Long id) { return candidates.findDetailedByProductId(id).orElseThrow(() ->
            new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的尋源候選")); }
    private String write(Object value) { try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("無法序列化尋源訊號", e); } }
    private SourcingScoutResponse response(SourcingCandidate candidate) {
        Optional<HeatCompositeDaily> composite = candidate.getDrivingKeyword() == null
                ? Optional.empty()
                : heatComposites.findFirstByKeywordIdOrderByStatDateDesc(
                        candidate.getDrivingKeyword().getId());
        return SourcingScoutResponse.from(candidate, composite.orElse(null), mapper);
    }
}
