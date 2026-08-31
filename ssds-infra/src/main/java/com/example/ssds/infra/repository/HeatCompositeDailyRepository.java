package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.id.HeatCompositeDailyId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HeatCompositeDailyRepository
        extends JpaRepository<HeatCompositeDaily, HeatCompositeDailyId> {
    Optional<HeatCompositeDaily> findFirstByKeywordIdOrderByStatDateDesc(Long keywordId);

    List<HeatCompositeDaily> findByKeywordIdAndStatDateBetweenOrderByStatDateAsc(
            Long keywordId, LocalDate from, LocalDate to);
}
