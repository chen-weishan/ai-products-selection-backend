package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.infra.entity.HeatSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** 熱度來源（規格書 §7.2 heat_source、FR-14-2）。 */
@Repository
public interface HeatSourceRepository extends JpaRepository<HeatSource, Long> {

    Optional<HeatSource> findBySourceCode(HeatSourceCode sourceCode);

    List<HeatSource> findByEnabledTrue();

    List<HeatSource> findByAvailability(SourceAvailability availability);

    /**
     * §5.3.2 合成時可用的來源：啟用且非 UNAVAILABLE。
     * 呼叫端需自行把這些來源的權重重新正規化為總和 1 ——
     * 資料庫存的是「原始設定權重」，降級後的實際比例是運算結果，不落地。
     */
    @Query("""
            select s from HeatSource s
            where s.enabled = true
              and s.availability <> com.example.ssds.core.domain.SourceAvailability.UNAVAILABLE
            """)
    List<HeatSource> findContributingSources();
}
