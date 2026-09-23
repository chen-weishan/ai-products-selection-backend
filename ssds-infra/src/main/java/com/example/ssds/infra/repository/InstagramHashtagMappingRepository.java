package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.InstagramHashtagMapping;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Instagram hashtag→品類對照（V29，取代 ssds-api/config 的靜態常數清單）。 */
@Repository
public interface InstagramHashtagMappingRepository extends JpaRepository<InstagramHashtagMapping, Long> {

    /**
     * 目前啟用要追蹤的 hashtag，join fetch 品類避免
     * {@code InstagramHeatIngestJob} 逐筆觸發 N+1。
     */
    @Query("select m from InstagramHashtagMapping m join fetch m.category where m.enabled = true")
    List<InstagramHashtagMapping> findAllEnabledWithCategory();
}
