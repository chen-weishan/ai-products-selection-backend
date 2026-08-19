package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.ImportBatch;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 匯入批次（規格書 §7.2 import_batch、FR-09）。 */
@Repository
public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    Page<ImportBatch> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<ImportBatch> findByDataTypeOrderByCreatedAtDesc(ImportDataType dataType);

    List<ImportBatch> findByStatus(TaskStatus status);
}
