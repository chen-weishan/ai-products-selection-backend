package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AiTaskItem;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** AI 任務逐項結果（規格書 §7.2 ai_task_item）。 */
@Repository
public interface AiTaskItemRepository extends JpaRepository<AiTaskItem, Long> {

    @EntityGraph(attributePaths = {"product", "keyword", "calibrationReport"})
    List<AiTaskItem> findByTaskId(Long taskId);

    /** FR-07「重跑失敗項」的取件範圍。 */
    @EntityGraph(attributePaths = {"product", "keyword", "calibrationReport"})
    List<AiTaskItem> findByTaskIdAndStatus(Long taskId, TaskItemStatus status);

    @Query("select i.id from AiTaskItem i where i.task.id = :taskId and i.status = :status order by i.id")
    List<Long> findIdsByTaskIdAndStatus(
            @Param("taskId") Long taskId,
            @Param("status") TaskItemStatus status
    );

    @EntityGraph(attributePaths = {"task", "product", "product.category"})
    @Query("select i from AiTaskItem i where i.id = :id")
    java.util.Optional<AiTaskItem> findForProcessing(@Param("id") Long id);

    long countByTaskIdAndStatus(Long taskId, TaskItemStatus status);

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

    @Query("""
            select distinct i.keyword.id
            from AiTaskItem i
            where i.keyword.id in :keywordIds
              and i.task.taskType = :taskType
              and i.task.status in :statuses
            """)
    Set<Long> findKeywordIdsInActiveTasks(
            @Param("keywordIds") Set<Long> keywordIds,
            @Param("taskType") AiTaskType taskType,
            @Param("statuses") Set<TaskStatus> statuses
    );

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
