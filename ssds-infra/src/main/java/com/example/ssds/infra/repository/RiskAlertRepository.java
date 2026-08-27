package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.entity.RiskAlert;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 風險示警（規格書 §7.2 risk_alert、FR-10）。
 *
 * <p>
 * AC-10-2：預設清單排除 IGNORED，但可用篩選查回來 ——
 * 所以「預設查詢」與「全部查詢」是兩支方法，不是一支加旗標。
 */
@Repository
public interface RiskAlertRepository extends JpaRepository<RiskAlert, Long> {

    /** 預設清單：未忽略者。 */
    @EntityGraph(attributePaths = { "product", "product.category" })
    Page<RiskAlert> findByStatusNot(AlertStatus status, Pageable pageable);

    @EntityGraph(attributePaths = { "product", "product.category" })
    Page<RiskAlert> findByStatusAndSeverity(AlertStatus status, Severity severity, Pageable pageable);

    List<RiskAlert> findByProductIdOrderByDetectedAtDesc(Long productId);

    /** 儀表板的高風險計數。 */
    long countByStatusAndSeverity(AlertStatus status, Severity severity);

    boolean existsByProductIdAndRiskTypeAndStatus(
            Long productId, String riskType, AlertStatus status);

    /** FR-02 KPI：未處理風險數量（不限嚴重度） */
    long countByStatus(AlertStatus status);

    /**
     * FR-02 儀表板排行風險指示：取得多個品項的最高嚴重度風險。
     * 回傳 Map<productId, severity>，僅包含 status = OPEN 的風險。
     */
    @Query("""
            SELECT r.product.id, MAX(r.severity)
            FROM RiskAlert r
            WHERE r.product.id IN :productIds AND r.status = com.example.ssds.core.domain.AlertStatus.OPEN
            GROUP BY r.product.id
            """)
    Map<Long, Severity> findMaxSeverityByProductIds(@Param("productIds") List<Long> productIds);
}
