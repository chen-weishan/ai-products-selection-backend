package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.ReportType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.ReportJob;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 報表產出任務（規格書 §7.2.11 report_job、FR-12）。 */
@Repository
public interface ReportJobRepository extends JpaRepository<ReportJob, Long> {

    /** §8.1 `GET /reports` 的清單來源；排序與 idx_report_job_requester 一致。 */
    Page<ReportJob> findByRequestedByIdOrderByRequestedAtDesc(Long userId, Pageable pageable);

    List<ReportJob> findByStatus(TaskStatus status);

    List<ReportJob> findByReportTypeOrderByRequestedAtDesc(ReportType reportType);
}
