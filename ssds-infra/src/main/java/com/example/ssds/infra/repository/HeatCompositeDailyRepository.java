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

/**
 * v3.0 §7.2.3 heat_composite_daily。
 *
 * <p>之前只有 entity、沒有 repository——{@code TrendService} 走
 * {@code TrendQueryDao}（唯讀），沒有任何寫入路徑，這也是「applied_weights
 * 全部來自種子 SQL、沒有批次任務會產生新的一天」這個缺口的一部分。
 */
@Repository
public interface HeatCompositeDailyRepository
        extends JpaRepository<HeatCompositeDaily, HeatCompositeDailyId> {

    Optional<HeatCompositeDaily> findByKeywordIdAndStatDate(Long keywordId, LocalDate statDate);

    Optional<HeatCompositeDaily> findFirstByKeywordIdOrderByStatDateDesc(Long keywordId);

    long countByStatDateAndKeywordEnabledTrue(LocalDate statDate);

    @Query(value = """
            select keyword.id
            from trend_keyword keyword
            left join heat_composite_daily composite
              on composite.keyword_id = keyword.id
             and composite.stat_date = :statDate
            where keyword.enabled = true
              and composite.keyword_id is null
            order by keyword.id
            """, nativeQuery = true)
    List<Long> findEnabledKeywordIdsMissingStatDate(@Param("statDate") LocalDate statDate);

    List<HeatCompositeDaily> findByKeywordIdAndStatDateBetweenOrderByStatDateAsc(
            Long keywordId, LocalDate from, LocalDate to);

    List<HeatCompositeDaily> findByKeywordIdAndStatDateBeforeOrderByStatDateDesc(
            Long keywordId, LocalDate before);

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
