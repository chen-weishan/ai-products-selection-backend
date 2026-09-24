package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.AiBudgetUsageDaily;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AiBudgetUsageDailyRepository extends JpaRepository<AiBudgetUsageDaily, Long> {
    List<AiBudgetUsageDaily> findByUsageDate(LocalDate usageDate);

    @Modifying
    @Query(value = """
            INSERT INTO ai_budget_usage_daily
                (usage_date, budget_pool, request_count, cache_hit_count, updated_at)
            VALUES (:usageDate, :budgetPool, :requestDelta, :cacheHitDelta, now())
            ON CONFLICT (usage_date, budget_pool) DO UPDATE
            SET request_count = ai_budget_usage_daily.request_count + EXCLUDED.request_count,
                cache_hit_count = ai_budget_usage_daily.cache_hit_count + EXCLUDED.cache_hit_count,
                updated_at = now()
            """, nativeQuery = true)
    void increment(
            @Param("usageDate") LocalDate usageDate,
            @Param("budgetPool") String budgetPool,
            @Param("requestDelta") int requestDelta,
            @Param("cacheHitDelta") int cacheHitDelta);
}
