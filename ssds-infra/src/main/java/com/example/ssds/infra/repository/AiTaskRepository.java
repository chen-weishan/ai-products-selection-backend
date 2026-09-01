package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AiTask;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** AI 任務（規格書 §7.2 ai_task、FR-07）。 */
@Repository
public interface AiTaskRepository extends JpaRepository<AiTask, Long> {

    Page<AiTask> findAllByOrderByStartedAtDesc(Pageable pageable);

    List<AiTask> findByStatus(TaskStatus status);

    List<AiTask> findByTaskTypeOrderByStartedAtDesc(AiTaskType taskType);

    boolean existsByTaskTypeAndStatusIn(AiTaskType taskType, List<TaskStatus> statuses);

    /** 當日任務總請求、其中 RETRY 池請求與快取命中，供服務重啟後恢復三池計數。 */
    @Query("""
            select t.budgetPool,
                   coalesce(sum(t.requestCount), 0),
                   coalesce(sum(t.retryPoolRequestCount), 0),
                   coalesce(sum(t.cacheHitCount), 0)
            from AiTask t
            where t.startedAt >= :since
            group by t.budgetPool
            """)
    List<Object[]> summarizeBudgetUsageSince(@Param("since") Instant since);

    /** 同品項的尋源任務尚未結束時直接沿用，避免重複 Web Search 造成逾時與 429。 */
    @Query("""
            select t
            from AiTaskItem i join i.task t
            where i.product.id = :productId
              and t.taskType = :taskType
              and t.status in :statuses
            order by t.id desc
            """)
    List<AiTask> findActiveProductTasks(
            @Param("productId") Long productId,
            @Param("taskType") AiTaskType taskType,
            @Param("statuses") List<TaskStatus> statuses,
            Pageable pageable);

    /**
     * 某段期間某組任務類型的累計花費，供 FR-07 的預算池計算。
     * 以 taskType 集合傳入而非 budgetPool，是因為池別是 Java 端列舉的屬性，
     * 資料庫只認得 task_type。
     */
    @Query("""
            select coalesce(sum(t.totalCostUsd), 0)
            from AiTask t
            where t.taskType in :taskTypes and t.startedAt >= :since
            """)
    BigDecimal sumCostByTaskTypes(
            @Param("taskTypes") List<AiTaskType> taskTypes, @Param("since") Instant since);
}
