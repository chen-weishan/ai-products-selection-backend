package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.TrendKeyword;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TrendKeywordRepository extends JpaRepository<TrendKeyword, Long> {

    long countByEnabledTrue();

    Optional<TrendKeyword> findByKeyword(String keyword);

    List<TrendKeyword> findAllByOrderByKeywordAsc();

    List<TrendKeyword> findByKeywordContainingIgnoreCaseOrderByKeywordAsc(
            String keyword
    );

    List<TrendKeyword> findByEnabledOrderByKeywordAsc(boolean enabled);

    List<TrendKeyword> findByKeywordContainingIgnoreCaseAndEnabledOrderByKeywordAsc(
            String keyword,
            boolean enabled
    );

    /** 每日 06:00 熱度採集的取件範圍（§5.10）。 */
    List<TrendKeyword> findByEnabledTrue();

}
