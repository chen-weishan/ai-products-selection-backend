package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.infra.entity.AiTaskItem;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.example.ssds.core.domain.AiTaskType;

/** AI 任務逐項結果（規格書 §7.2 ai_task_item）。 */
@Repository
public interface AiTaskItemRepository extends JpaRepository<AiTaskItem, Long> {

    @EntityGraph(attributePaths = {"product", "keyword", "calibrationReport"})
    List<AiTaskItem> findByTaskId(Long taskId);

    /** FR-07「重跑失敗項」的取件範圍。 */
    @EntityGraph(attributePaths = {"product", "keyword", "calibrationReport"})
    List<AiTaskItem> findByTaskIdAndStatus(Long taskId, TaskItemStatus status);

    long countByTaskIdAndStatus(Long taskId, TaskItemStatus status);

    /** 配額耗盡或單輪上限超出的 FULL_ANALYSIS 品項，供隔日續跑。 */
    @Query("""
            select distinct p from AiTaskItem i join i.product p
            where i.task.taskType = :taskType
              and i.status = :status
              and p.deletedAt is null
              and not exists (
                  select newer.id from AiTaskItem newer
                  where newer.product.id = i.product.id
                    and newer.task.taskType = :taskType
                    and newer.id > i.id
                    and newer.status in (
                        com.example.ssds.core.domain.TaskItemStatus.SUCCEEDED,
                        com.example.ssds.core.domain.TaskItemStatus.SKIPPED_CACHE)
              )
            order by p.id
            """)
    List<com.example.ssds.infra.entity.Product> findProductsPendingQuotaRetry(
            @Param("taskType") AiTaskType taskType,
            @Param("status") TaskItemStatus status);
}
