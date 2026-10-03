package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.TrendInterpretation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TrendInterpretationRepository extends JpaRepository<TrendInterpretation, Long> {
    Optional<TrendInterpretation> findByKeywordIdAndCurrentTrue(Long keywordId);

    @Modifying
    @Query("update TrendInterpretation t set t.current = false where t.keyword.id = :keywordId and t.current = true")
    int demoteCurrent(@Param("keywordId") Long keywordId);
}
