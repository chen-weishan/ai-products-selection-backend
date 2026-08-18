package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.Category;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** 品類查詢（規格書 §7.2 category）。 */
@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** 頂層類別，依排序欄位。 */
    List<Category> findByParentIsNullOrderBySortOrderAsc();

    List<Category> findByParentIdOrderBySortOrderAsc(Long parentId);

    /**
     * 一次撈完兩層，供前端下拉選單使用。
     * 用 join fetch 而不是讓呼叫端逐一觸發 children，避免 N+1。
     */
    @Query("select distinct c from Category c left join fetch c.children "
            + "where c.parent is null order by c.sortOrder")
    List<Category> findTreeWithChildren();
}
