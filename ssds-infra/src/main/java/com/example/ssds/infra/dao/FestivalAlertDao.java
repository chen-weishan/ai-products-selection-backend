package com.example.ssds.infra.dao;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.example.ssds.infra.dao.projection.FestivalAlertRow;

/**
 * FR-10-1 {@code FESTIVAL_WINDOW_CLOSING} 的批次讀取：一次 SQL 取回全部
 * 「這一輪該節慶尚未建立決策的品項 × 其關聯的未到節慶」，不逐品項查詢（避免 N+1）。
 *
 * <p>範圍：
 * <ul>
 *   <li>品項未軟刪除、狀態不是 {@code REJECTED}；<b>A、B 軌都納入</b></li>
 *   <li><b>該節慶這一輪尚未建立決策</b>：{@code decision_record} 沒有「決策日（台北時區）晚於
 *       去年同一節慶日」的任何一列（不分 ADOPT／WATCH／REJECT）。去年或更早做的決策不算——
 *       決策沒有綁定節慶，舊決策不能代表今年已經決定。這個判斷是<b>逐節慶</b>的，
 *       所以放在 join 條件裡，不是品項層級的過濾</li>
 *   <li>關聯度 &gt; 0（關聯度 0 的節慶對分數沒有貢獻，窗關閉也沒有可損失的東西）</li>
 *   <li>節慶日 ≥ 偵測日，且在「偵測日 + 前置天數 + 30」之內——窗權重要等於 1.0 至少得落在這個範圍，
 *       先在 SQL 裡砍掉其餘列，判定留給 {@code FestivalWindow}（只維護一份時間窗公式）</li>
 * </ul>
 *
 * <p>品類沒有 {@code category_lead_time} 時用 left join 保留該列、{@code lead_time_days} 為 null，
 * 讓呼叫端能數出「因缺前置天數而略過幾個品項」。
 */
@Repository
public class FestivalAlertDao {

    private final JdbcClient jdbcClient;

    public FestivalAlertDao(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<FestivalAlertRow> findUpcomingFestivalsWithoutDecision(LocalDate today) {
        return jdbcClient
                .sql("""
                        select p.id             as product_id,
                               p.category_id    as category_id,
                               clt.lead_time_days as lead_time_days,
                               fc.festival_code as festival_code,
                               fc.festival_name as festival_name,
                               fc.festival_date as festival_date,
                               ifa.affinity     as affinity
                        from product p
                        join item_festival_affinity ifa on ifa.product_id = p.id and ifa.affinity > 0
                        join festival_calendar fc on fc.festival_code = ifa.festival_code
                        left join category_lead_time clt on clt.category_id = p.category_id
                        where p.deleted_at is null
                          and p.status <> 'REJECTED'
                          and not exists (
                              select 1
                              from decision_record d
                              where d.product_id = p.id
                                and (d.decided_at at time zone 'Asia/Taipei')::date
                                    > cast(fc.festival_date - interval '1 year' as date)
                          )
                          and fc.festival_date >= cast(:today as date)
                          and fc.festival_date <= cast(:today as date) + (coalesce(clt.lead_time_days, 0) + 30)
                        order by p.id, fc.festival_date, fc.festival_code
                        """)
                .param("today", today)
                .query((rs, rowNum) -> new FestivalAlertRow(
                        rs.getLong("product_id"),
                        rs.getLong("category_id"),
                        rs.getObject("lead_time_days", Integer.class),
                        rs.getString("festival_code"),
                        rs.getString("festival_name"),
                        rs.getObject("festival_date", LocalDate.class),
                        rs.getBigDecimal("affinity")))
                .list();
    }
}
