package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.TrendDaily;
import com.example.ssds.infra.entity.id.TrendDailyId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 每日熱度查詢（規格書 §7.2 trend_daily）。
 *
 * <p>§7.3：主鍵 (keyword_id, stat_date) 即為查詢鍵，
 * 90 日區間查詢是索引範圍掃描，不需要額外索引。
 */
@Repository
public interface TrendDailyRepository extends JpaRepository<TrendDaily, TrendDailyId> {

    /** FR-06 趨勢折線圖的資料來源。 */
    List<TrendDaily> findByKeywordIdAndStatDateBetweenOrderByStatDateAsc(
            Long keywordId, LocalDate from, LocalDate to);

    /** §5.3.3 斜率計算需要「t 日」與「t−7／t−30 日」兩個點。 */
    Optional<TrendDaily> findByKeywordIdAndStatDate(Long keywordId, LocalDate statDate);

    /** 儀表板一次取多個關鍵字的區間資料，避免逐一查詢。 */
    @Query("""
            select t from TrendDaily t
            where t.keyword.id in :keywordIds
              and t.statDate between :from and :to
            order by t.keyword.id, t.statDate
            """)
    List<TrendDaily> findRangeForKeywords(
            @Param("keywordIds") List<Long> keywordIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
