package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.id.HeatCompositeDailyId;
import java.time.LocalDate;
import java.util.List;
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

    /**
     * §FR-06 {@code stage_weeks} 規則式算法用：一次撈出指定關鍵字在
     * {@code [from, to]} 範圍內的每日列，由新到舊排序，供服務層往前逐日回溯、
     * 統計與當日相同 {@code stage} 的連續天數（中斷即停止），避免逐日 N+1 查詢。
     *
     * <p>呼叫端應傳入足夠寬的 {@code from}（例如往前 60～90 天）以涵蓋可能的
     * 最長連續區段；若連續天數觸及 {@code from} 邊界，代表歷史可能更長，
     * 依規格「無足夠歷史時以現有天數計算並於 UI 標示」處理。
     */
    List<HeatCompositeDaily> findByKeywordIdAndStatDateBetweenOrderByStatDateDesc(
            Long keywordId, LocalDate from, LocalDate to);
}