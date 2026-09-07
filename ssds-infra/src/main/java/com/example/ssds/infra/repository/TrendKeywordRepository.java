package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.TrendKeyword;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TrendKeywordRepository extends JpaRepository<TrendKeyword, Long> {

    Optional<TrendKeyword> findByKeyword(String keyword);

    /** 每日 06:00 熱度採集的取件範圍（§5.10）。 */
    List<TrendKeyword> findByEnabledTrue();

}