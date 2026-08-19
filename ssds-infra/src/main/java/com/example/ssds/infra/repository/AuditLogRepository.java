package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.AuditLog;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 稽核紀錄（規格書 §7.2 audit_log）。 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @EntityGraph(attributePaths = {"user"})
    Page<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            String entityType, Long entityId, Pageable pageable);

    @EntityGraph(attributePaths = {"user"})
    Page<AuditLog> findByCreatedAtBetween(Instant from, Instant to, Pageable pageable);

    @EntityGraph(attributePaths = {"user"})
    Page<AuditLog> findByUserId(Long userId, Pageable pageable);
}
