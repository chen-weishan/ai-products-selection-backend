package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.id.HeatCompositeDailyId;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface HeatCompositeDailyRepository
        extends JpaRepository<HeatCompositeDaily, HeatCompositeDailyId> {
    Optional<HeatCompositeDaily> findFirstByKeywordIdOrderByStatDateDesc(Long keywordId);

    List<HeatCompositeDaily> findByKeywordIdAndStatDateBetweenOrderByStatDateAsc(
            Long keywordId, LocalDate from, LocalDate to);

    /** 每個關鍵字只取最新列，且至少已有七筆合成資料，供 §5.3.3 選生效關鍵字。 */
    @Query(value = """
            select distinct on (h.keyword_id) h.*
            from heat_composite_daily h
            where h.keyword_id in (:keywordIds)
              and (select count(*) from heat_composite_daily history
                   where history.keyword_id = h.keyword_id) >= 7
            order by h.keyword_id, h.stat_date desc
            """, nativeQuery = true)
    List<HeatCompositeDaily> findLatestEligibleForDrivingKeyword(
            @Param("keywordIds") Collection<Long> keywordIds);
}
