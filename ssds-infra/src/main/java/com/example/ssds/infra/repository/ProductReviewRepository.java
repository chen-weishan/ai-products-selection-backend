package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.ProductReview;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** 評論查詢（規格書 §7.2 product_review）。 */
@Repository
public interface ProductReviewRepository extends JpaRepository<ProductReview, Long> {

    @EntityGraph(attributePaths = {"analysis"})
    Page<ProductReview> findByProductId(Long productId, Pageable pageable);

    /** 匯入前的重複檢查；正常路徑靠唯一鍵擋，這支供預覽階段提示使用者。 */
    boolean existsByProductIdAndContentHash(Long productId, String contentHash);

    /** 尚未分析的評論，供 ReviewRiskAgent 批次處理。 */
    @Query("select r from ProductReview r where r.analysis is null and r.product.id = :productId")
    List<ProductReview> findUnanalyzedByProduct(@Param("productId") Long productId);

    long countByProductId(Long productId);
}
