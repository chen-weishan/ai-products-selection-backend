package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AiTaskItem;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** AI 任務逐項結果（規格書 §7.2 ai_task_item）。 */
@Repository
public interface AiTaskItemRepository extends JpaRepository<AiTaskItem, Long> {

    List<AiTaskItem> findByTaskId(Long taskId);

    /** FR-07「重跑失敗項」的取件範圍。 */
    List<AiTaskItem> findByTaskIdAndStatus(Long taskId, TaskStatus status);

    long countByTaskIdAndStatus(Long taskId, TaskStatus status);

    @Query("""
            select distinct i.product.id
            from AiTaskItem i
            where i.product.id in :productIds
              and i.task.taskType = :taskType
              and i.task.status in :statuses
            """)
    Set<Long> findProductIdsInActiveTasks(
            @Param("productIds") Set<Long> productIds,
            @Param("taskType") AiTaskType taskType,
            @Param("statuses") Set<TaskStatus> statuses
    );
}
