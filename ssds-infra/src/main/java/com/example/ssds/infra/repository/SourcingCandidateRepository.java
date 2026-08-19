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

/** B 軌尋源候選（規格書 §7.2 sourcing_candidate、FR-16-2）。 */
@Repository
public interface SourcingCandidateRepository extends JpaRepository<SourcingCandidate, Long> {

    /**
     * 尋源優先序清單。AC-16-2：<b>以時效落差為主排序依據，不是熱度</b> ——
     * 熱度最高但來不及的品項排在前面不具意義。
     * 已淘汰者灰底保留，供下次同關鍵字出現時參考，所以不過濾掉 REJECTED。
     */
    @EntityGraph(attributePaths = {"keyword", "category"})
    @Query("select c from SourcingCandidate c order by c.timeGapDays asc, c.status asc")
    List<SourcingCandidate> findPriorityList();

    @EntityGraph(attributePaths = {"keyword", "category"})
    List<SourcingCandidate> findByStatusOrderByTimeGapDaysAsc(SourcingStatus status);

    Optional<SourcingCandidate> findByKeywordId(Long keywordId);

    List<SourcingCandidate> findByHeatStage(HeatStage heatStage);
}
