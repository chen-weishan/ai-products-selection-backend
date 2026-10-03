package com.example.ssds.api.scoring.factor;

import com.example.ssds.api.scoring.ScoreEvaluationService.FactorInput;
import com.example.ssds.api.scoring.factor.FactorComputationService.Evidence;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.Product;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** §5.10.1 兩階段因子批次：先收齊 raw values，再以完整母體產生百分位。 */
@Service
public class ScoringFactorBatchService {
    private static final List<FactorCode> BONUS_FACTORS = List.of(
            FactorCode.TREND,
            FactorCode.MARGIN,
            FactorCode.CVR,
            FactorCode.PRICE_FIT,
            FactorCode.FESTIVAL,
            FactorCode.CLIMATE);

    private final ScoringFactorEvidenceLoader evidenceLoader;
    private final FactorComputationService computationService;

    public ScoringFactorBatchService(
            ScoringFactorEvidenceLoader evidenceLoader,
            FactorComputationService computationService) {
        this.evidenceLoader = evidenceLoader;
        this.computationService = computationService;
    }

    @Transactional(readOnly = true)
    public Map<Long, Map<FactorCode, FactorInput>> prepare(
            List<Product> products, LocalDate evaluationDate) {
        return preparePopulation(products, evaluationDate).allFactors();
    }

    @Transactional(readOnly = true)
    public PreparedPopulation preparePopulation(
            List<Product> products, LocalDate evaluationDate) {
        LinkedHashMap<Long, Evidence> rawEvidence = new LinkedHashMap<>();
        LinkedHashMap<Long, Map<FactorCode, FactorInput>> rawInputs = new LinkedHashMap<>();
        for (Product product : products) {
            Evidence evidence = evidenceLoader.loadRaw(product, evaluationDate);
            rawEvidence.put(product.getId(), evidence);
            rawInputs.put(product.getId(), computationService.compute(evidence));
        }

        LinkedHashMap<Long, Map<FactorCode, PercentileBasis>> basesByProduct = new LinkedHashMap<>();
        LinkedHashMap<Long, Map<FactorCode, FactorInput>> allFactors = new LinkedHashMap<>();
        for (Product product : products) {
            EnumMap<FactorCode, PercentileBasis> bases = new EnumMap<>(FactorCode.class);
            for (FactorCode code : BONUS_FACTORS) {
                bases.put(code, basis(product, code, products, rawInputs));
            }
            basesByProduct.put(product.getId(), Map.copyOf(bases));
            allFactors.put(product.getId(), computationService.compute(
                    rawEvidence.get(product.getId()).withBases(bases)));
        }
        return new PreparedPopulation(
                evaluationDate,
                products.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Product::getId, product -> product)),
                Map.copyOf(basesByProduct),
                Map.copyOf(allFactors));
    }

    /**
     * Agent 2 完成後只重載目標品項 evidence；六項加分因子的百分位母體沿用同一 task 的準備結果。
     */
    @Transactional(readOnly = true)
    public Map<FactorCode, FactorInput> refreshTarget(
            PreparedPopulation population, Long productId) {
        Product product = population.products().get(productId);
        if (product == null) {
            throw new IllegalArgumentException("品項不在已準備的因子母體：" + productId);
        }
        Map<FactorCode, PercentileBasis> bases = population.basesByProduct().get(productId);
        if (bases == null) {
            throw new IllegalStateException("因子母體未包含品項基準：" + productId);
        }
        return computationService.compute(
                evidenceLoader.loadRaw(product, population.evaluationDate()).withBases(bases));
    }

    public record PreparedPopulation(
            LocalDate evaluationDate,
            Map<Long, Product> products,
            Map<Long, Map<FactorCode, PercentileBasis>> basesByProduct,
            Map<Long, Map<FactorCode, FactorInput>> allFactors) {}

    private static PercentileBasis basis(
            Product target,
            FactorCode code,
            List<Product> products,
            Map<Long, Map<FactorCode, FactorInput>> rawInputs) {
        List<Product> sameCategory = available(
                products,
                product -> product.getCategory().getId().equals(target.getCategory().getId()),
                code,
                rawInputs);
        if (sameCategory.size() >= 10) {
            return new PercentileBasis(peerValues(target, sameCategory, code, rawInputs), false, null);
        }

        Category parent = target.getCategory().getParent();
        if (sameCategory.size() >= 3 && parent != null) {
            List<Product> siblings = available(
                    products,
                    product -> product.getCategory().getParent() != null
                            && parent.getId().equals(product.getCategory().getParent().getId()),
                    code,
                    rawInputs);
            return new PercentileBasis(
                    peerValues(target, siblings, code, rawInputs),
                    true,
                    "同品類樣本不足 10，以兄弟品類合併母體計算");
        }

        List<Product> global = available(products, ignored -> true, code, rawInputs);
        return new PercentileBasis(
                peerValues(target, global, code, rawInputs),
                true,
                "同品類樣本不足 3，以全品類母體計算");
    }

    private static List<Product> available(
            List<Product> products,
            Predicate<Product> filter,
            FactorCode code,
            Map<Long, Map<FactorCode, FactorInput>> rawInputs) {
        return products.stream()
                .filter(filter)
                .filter(product -> rawInputs.get(product.getId()).get(code).rawValue() != null)
                .toList();
    }

    private static List<BigDecimal> peerValues(
            Product target,
            List<Product> population,
            FactorCode code,
            Map<Long, Map<FactorCode, FactorInput>> rawInputs) {
        return population.stream()
                .filter(product -> !product.getId().equals(target.getId()))
                .map(product -> rawInputs.get(product.getId()).get(code).rawValue())
                .toList();
    }
}
