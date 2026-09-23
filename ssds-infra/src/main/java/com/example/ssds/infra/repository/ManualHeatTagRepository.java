package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.ManualHeatTag;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** 人工熱度標記（規格書 §7.2 manual_heat_tag、FR-14-1）。 */
@Repository
public interface ManualHeatTagRepository extends JpaRepository<ManualHeatTag, Long> {

    /** 單筆詳情／編輯／刪除前查詢：一次帶出關聯，避免畫面顯示時逐一觸發 lazy 查詢。 */
    @EntityGraph(attributePaths = {"taggedBy", "product", "keyword"})
    Optional<ManualHeatTag> findWithDetailsById(Long id);

    /**
     * 某品項的全部標記（含已失效者，是否失效由呼叫端依 observedAt 自行判斷）。
     * 用於 S-15 列表畫面與每日排程的聚合計算。
     */
    @EntityGraph(attributePaths = {"taggedBy", "product", "keyword"})
    List<ManualHeatTag> findByProductIdOrderByObservedAtDesc(Long productId);

    /** 同上，關鍵字版本。 */
    @EntityGraph(attributePaths = {"taggedBy", "product", "keyword"})
    List<ManualHeatTag> findByKeywordIdOrderByObservedAtDesc(Long keywordId);

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

    /**
     * 每日排程（ManualHeatReadingJob）用：找出「還可能有未失效標記」的品項／關鍵字 id。
     * 用 {@code since}（評估日 − 失效天數）先在 DB 端過濾掉早就失效的標記，
     * 避免每天把 manual_heat_tag 全表撈進記憶體。
     */
    @Query("select distinct t.product.id from ManualHeatTag t "
            + "where t.product is not null and t.observedAt >= :since")
    List<Long> findDistinctProductIdsByObservedAtAfter(@Param("since") Instant since);

    @Query("select distinct t.keyword.id from ManualHeatTag t "
            + "where t.keyword is not null and t.observedAt >= :since")
    List<Long> findDistinctKeywordIdsByObservedAtAfter(@Param("since") Instant since);

    /** §FR-14-2 健康檢查：人工標記來源改為檢查「最近 30 日是否有標記」。 */
    boolean existsByObservedAtAfter(Instant since);

    /**
     * §8 API 表 {@code GET /heat-tags?scope=&days=} 的「全部」範圍：不分品項／關鍵字，
     * 列出 {@code since} 之後觀察到的全部標記（供 S-15 列表畫面一般瀏覽用）。
     */
    @EntityGraph(attributePaths = {"taggedBy", "product", "keyword"})
    List<ManualHeatTag> findByObservedAtAfterOrderByObservedAtDesc(Instant since);

    /** 同上，{@code scope=mine}：僅本人建立的標記。 */
    @EntityGraph(attributePaths = {"taggedBy", "product", "keyword"})
    List<ManualHeatTag> findByTaggedByIdAndObservedAtAfterOrderByObservedAtDesc(
            Long taggedById, Instant since);
}
