package com.example.ssds.infra.dao;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.example.ssds.infra.dao.projection.HeatAlertRow;

/**
 * FR-10-2 熱度異常示警（HEAT_CRASH／HEAT_SURGE）的批次讀取。
 *
 * <p>一次 SQL 取回偵測日全部「品項 × 關聯關鍵字」的斜率，不逐品項查詢（避免 N+1）。
 *
 * <p>範圍（§FR-10-2「所有 enabled 的關鍵字所關聯的品項」）：
 * <ul>
 *   <li>關鍵字 {@code enabled = true}</li>
 *   <li>偵測日有 {@code heat_composite_daily} 列，且 {@code slope_7d} 非 null——
 *       歷史不足 7 日的關鍵字不參與（§5.3.3 同一慣例，沒有斜率就無從判定）</li>
 *   <li>品項未軟刪除（{@code product.deleted_at IS NULL}）</li>
 * </ul>
 *
 * <p>排序：同一品項內 {@code slope_7d} 由大到小（同值再依關鍵字 id），
 * 品項的生效關鍵字取第一列，與 §5.3.3「多關鍵字取最高者」的慣例一致。
 */
@Repository
public class HeatAlertDao {

    private final JdbcClient jdbcClient;

    public HeatAlertDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<HeatAlertRow> findProductHeat(LocalDate statDate) {
        return jdbcClient
                .sql("""
                        select p.id            as product_id,
                               p.category_id   as category_id,
                               c.parent_id     as parent_category_id,
                               p.status        as status,
                               k.id            as keyword_id,
                               k.keyword       as keyword,
                               h.slope_7d      as slope_7d,
                               h.volume_below_floor as volume_below_floor
                        from product p
                        join category c on c.id = p.category_id
                        join product_keyword pk on pk.product_id = p.id
                        join trend_keyword k on k.id = pk.keyword_id and k.enabled = true
                        join heat_composite_daily h on h.keyword_id = k.id and h.stat_date = :statDate
                        where p.deleted_at is null
                          and h.slope_7d is not null
                        order by p.id, h.slope_7d desc, k.id
                        """)
                .param("statDate", statDate)
                .query((rs, rowNum) -> new HeatAlertRow(
                        rs.getLong("product_id"),
                        rs.getLong("category_id"),
                        rs.getObject("parent_category_id", Long.class),
                        rs.getString("status"),
                        rs.getLong("keyword_id"),
                        rs.getString("keyword"),
                        rs.getBigDecimal("slope_7d"),
                        rs.getBoolean("volume_below_floor")))
                .list();
    }
}
