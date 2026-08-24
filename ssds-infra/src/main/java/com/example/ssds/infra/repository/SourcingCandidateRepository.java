package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.infra.entity.SourcingCandidate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * B 軌尋源候選（規格書 §7.2.9 sourcing_candidate、FR-16-2）。
 *
 * <p>狀態不在本實體上：v3.0 §7.2.9 明訂一律以 {@code product.sourcingStatus}
 * 為準，不重複於 sourcing_candidate。因此所有依狀態的查詢都要 join 過去。
 */
@Repository
public interface SourcingCandidateRepository extends JpaRepository<SourcingCandidate, Long> {

    /**
     * 尋源優先序清單。AC-16-2：<b>以時效落差為主排序依據，不是熱度</b> ——
     * 熱度最高但來不及的品項排在前面不具意義。
     * 已淘汰者灰底保留，供下次同關鍵字出現時參考，所以不過濾掉 REJECTED。
     *
     * <p>時效落差可能為 null（壽命尚未推估）。{@code nulls last} 是刻意的：
     * null 代表「還不知道來不來得及」，排在已知可行的品項前面會誤導。
     */
    @EntityGraph(attributePaths = {"product", "keyword", "category"})
    @Query("""
           select c from SourcingCandidate c
           order by c.timeGapDays asc nulls last, c.product.sourcingStatus asc
           """)
    List<SourcingCandidate> findPriorityList();

    @EntityGraph(attributePaths = {"product", "keyword", "category"})
    @Query("""
           select c from SourcingCandidate c
           where c.product.sourcingStatus = :status
           order by c.timeGapDays asc nulls last
           """)
    List<SourcingCandidate> findByProductSourcingStatus(SourcingStatus status);

    /** 一個品項最多一列候選（product_id UNIQUE，§7.2.9）。 */
    Optional<SourcingCandidate> findByProductId(Long productId);

    /**
     * 依來源關鍵字查。keyword_id 是「當初從哪個關鍵字挖出來」的歷史紀錄，
     * 可為 null 也可能與 product_keyword 的現況不一致，不要拿來當即時關聯。
     */
    List<SourcingCandidate> findByKeywordId(Long keywordId);

    List<SourcingCandidate> findByHeatStage(HeatStage heatStage);
}
