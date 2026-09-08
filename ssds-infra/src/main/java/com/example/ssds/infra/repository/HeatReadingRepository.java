package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.HeatReading;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** 各來源熱度讀值（規格書 §7.2 heat_reading）。走 uk_heat_reading。 */
@Repository
public interface HeatReadingRepository extends JpaRepository<HeatReading, Long> {

    @EntityGraph(attributePaths = {"source"})
    List<HeatReading> findByKeywordIdAndReadingDateBetweenOrderByReadingDateAsc(
            Long keywordId, LocalDate from, LocalDate to);

    /** Agent 5 同時讀關鍵字級訊號與該關鍵字所屬品類的品類級訊號。 */
    @EntityGraph(attributePaths = {"source", "category"})
    @Query("""
            select distinct reading from HeatReading reading
            where reading.readingDate between :from and :to
              and (
                    reading.keyword.id = :keywordId
                    or reading.category.id in (
                        select distinct product.category.id
                        from Product product join product.keywords keyword
                        where keyword.id = :keywordId
                    )
                  )
            order by reading.readingDate asc, reading.id asc
            """)
    List<HeatReading> findForKeywordIncludingCategorySources(
            @Param("keywordId") Long keywordId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    Optional<HeatReading> findByKeywordIdAndSourceIdAndReadingDate(
            Long keywordId, Long sourceId, LocalDate readingDate);

    /** 某日某來源的全部讀值，供「同來源內百分位化」批次計算（§5.3.2）。 */
    List<HeatReading> findBySourceIdAndReadingDate(Long sourceId, LocalDate readingDate);
}
