package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.infra.entity.AiTaskItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** AI 任務逐項結果（規格書 §7.2 ai_task_item）。 */
@Repository
public interface AiTaskItemRepository extends JpaRepository<AiTaskItem, Long> {

    List<AiTaskItem> findByTaskId(Long taskId);

    /** FR-07「重跑失敗項」的取件範圍。 */
    List<AiTaskItem> findByTaskIdAndStatus(Long taskId, TaskItemStatus status);

    long countByTaskIdAndStatus(Long taskId, TaskItemStatus status);
}
