package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.ClimateNormal;
import com.example.ssds.infra.entity.id.ClimateNormalId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 歷史同期氣候統計（規格書 §7.2 climate_normal）。
 *
 * <p>只有這份統計進評分。AC-17-4：短期天氣預報不得計入分數，
 * 因此本 repository 刻意沒有任何「預報」相關方法 —— 資料層就不提供，
 * 上層想誤用也沒有入口。
 */
@Repository
public interface ClimateNormalRepository extends JpaRepository<ClimateNormal, ClimateNormalId> {

    Optional<ClimateNormal> findByRegionCodeAndMonth(String regionCode, Short month);

    List<ClimateNormal> findByRegionCodeOrderByMonthAsc(String regionCode);
}
