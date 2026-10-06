package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.CalibrationStatus;
import com.example.ssds.infra.entity.CalibrationReport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** 權重校準報告（規格書 §7.2 calibration_report、FR-15）。 */
@Repository
public interface CalibrationReportRepository extends JpaRepository<CalibrationReport, Long> {

    Optional<CalibrationReport> findByQuarter(String quarter);

    List<CalibrationReport> findByStatusOrderByCreatedAtDesc(CalibrationStatus status);

    List<CalibrationReport> findAllByOrderByCreatedAtDesc();

    /** FR-12 校準報表範圍選單只顯示實際存在的季度。 */
    @Query("select distinct r.quarter from CalibrationReport r order by r.quarter desc")
    List<String> findDistinctQuartersOrderByQuarterDesc();
}
