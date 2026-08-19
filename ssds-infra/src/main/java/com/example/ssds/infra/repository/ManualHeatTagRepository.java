package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.ManualHeatTag;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** 人工熱度標記（規格書 §7.2 manual_heat_tag、FR-14-1）。 */
@Repository
public interface ManualHeatTagRepository extends JpaRepository<ManualHeatTag, Long> {

    @EntityGraph(attributePaths = {"taggedBy"})
    List<ManualHeatTag> findByProductIdAndObservedAtAfterOrderByObservedAtDesc(
            Long productId, Instant since);

    @EntityGraph(attributePaths = {"taggedBy"})
    List<ManualHeatTag> findByKeywordIdAndObservedAtAfterOrderByObservedAtDesc(
            Long keywordId, Instant since);

    /**
     * §5.3.2 的信心係數看的是「標記人數」而非標記筆數 ——
     * 同一個人連貼五則不會比較可信，所以這裡 count distinct tagged_by。
     */
    @Query("""
            select count(distinct t.taggedBy.id) from ManualHeatTag t
            where t.product.id = :productId and t.observedAt >= :since
            """)
    long countDistinctTaggersByProduct(
            @Param("productId") Long productId, @Param("since") Instant since);

    @Query("""
            select count(distinct t.taggedBy.id) from ManualHeatTag t
            where t.keyword.id = :keywordId and t.observedAt >= :since
            """)
    long countDistinctTaggersByKeyword(
            @Param("keywordId") Long keywordId, @Param("since") Instant since);
}
