package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.id.HeatCompositeDailyId;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * v3.0 §7.2.3 heat_composite_daily。
 *
 * <p>之前只有 entity、沒有 repository——{@code TrendService} 走
 * {@code TrendQueryDao}（唯讀），沒有任何寫入路徑，這也是「applied_weights
 * 全部來自種子 SQL、沒有批次任務會產生新的一天」這個缺口的一部分。
 */
@Repository
public interface HeatCompositeDailyRepository
        extends JpaRepository<HeatCompositeDaily, HeatCompositeDailyId> {

    Optional<HeatCompositeDaily> findByKeywordIdAndStatDate(Long keywordId, LocalDate statDate);
}
