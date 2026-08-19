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
