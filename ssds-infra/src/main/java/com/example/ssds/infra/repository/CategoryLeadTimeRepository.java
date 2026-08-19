package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.CategoryLeadTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 品類前置天數（規格書 §7.2 category_lead_time）。
 *
 * <p>AC-17-3：同時供 FR-17 節慶時間窗與 FR-16 時效落差使用，只有這一份。
 */
@Repository
public interface CategoryLeadTimeRepository extends JpaRepository<CategoryLeadTime, Long> {
}
