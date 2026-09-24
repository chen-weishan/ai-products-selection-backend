package com.example.ssds.ingest.importer;

import java.util.List;

/**
 * FR-09 品項比對規則：有 productId 時以 ID 為唯一依據；否則以正規化名稱比對，
 * 有類別時再用類別縮小範圍。零筆為 UNMATCHED，多筆為 AMBIGUOUS，不任意挑第一筆。
 */
public class ProductMatchingRule {

    public ProductMatchResult match(ProductMatchInput input, List<ProductCandidate> candidates) {
        if (input.productId() != null) {
            return candidates.stream()
                    .filter(candidate -> input.productId().equals(candidate.id()))
                    .findFirst()
                    .map(candidate -> new ProductMatchResult(ProductMatchStatus.MATCHED, candidate.id()))
                    .orElseGet(() -> new ProductMatchResult(ProductMatchStatus.UNMATCHED, null));
        }

        String name = ImportHeaderMapper.normalize(input.productName());
        String category = ImportHeaderMapper.normalize(input.category());
        if (name.isBlank()) {
            return new ProductMatchResult(ProductMatchStatus.UNMATCHED, null);
        }

        List<ProductCandidate> matches = candidates.stream()
                .filter(candidate -> name.equals(ImportHeaderMapper.normalize(candidate.name())))
                .filter(candidate -> category.isBlank()
                        || category.equals(ImportHeaderMapper.normalize(candidate.category())))
                .toList();
        if (matches.isEmpty()) {
            return new ProductMatchResult(ProductMatchStatus.UNMATCHED, null);
        }
        if (matches.size() > 1) {
            return new ProductMatchResult(ProductMatchStatus.AMBIGUOUS, null);
        }
        return new ProductMatchResult(ProductMatchStatus.MATCHED, matches.getFirst().id());
    }

    public record ProductMatchInput(Long productId, String productName, String category) {}

    public record ProductCandidate(Long id, String name, String category) {}

    public record ProductMatchResult(ProductMatchStatus status, Long productId) {}

    public enum ProductMatchStatus {
        MATCHED,
        UNMATCHED,
        AMBIGUOUS
    }
}
