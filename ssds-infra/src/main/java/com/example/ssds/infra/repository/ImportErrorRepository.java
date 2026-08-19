package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.ImportError;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 匯入錯誤明細（規格書 §7.2 import_error）。AC-09-2 的下載清單來源。 */
@Repository
public interface ImportErrorRepository extends JpaRepository<ImportError, Long> {

    List<ImportError> findByBatchIdOrderByRowNumberAsc(Long batchId);

    long countByBatchId(Long batchId);
}
