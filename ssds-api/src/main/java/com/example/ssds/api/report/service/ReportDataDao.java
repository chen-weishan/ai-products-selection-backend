package com.example.ssds.api.report.service;

import com.example.ssds.api.report.model.ReportColumn;
import com.example.ssds.api.report.model.ReportDataset;
import com.example.ssds.api.report.model.ReportSection;
import com.example.ssds.core.domain.ReportType;
import com.example.ssds.core.domain.SceneType;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReportDataDao {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
    private final JdbcClient jdbc;

    public ReportDataDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int estimateRows(ReportType type, Map<String, Object> params) {
        return switch (type) {
            case WEEKLY_PICK -> 60;
            case SCORE_DETAIL -> count("""
                    SELECT COUNT(*) FROM score_factor f
                    JOIN product_score s ON s.id = f.score_id
                    JOIN product p ON p.id = s.product_id
                    WHERE p.deleted_at IS NULL AND s.is_active = TRUE
                      AND s.period = :period
                      AND (CAST(:categoryId AS BIGINT) IS NULL OR p.category_id = :categoryId)
                    """, params);
            case ACCURACY -> countAccuracy(params);
            case SOURCING_QUEUE -> count("""
                    SELECT COUNT(*) FROM sourcing_candidate sc
                    JOIN product p ON p.id = sc.product_id
                    WHERE p.deleted_at IS NULL AND p.track_type = 'B'
                      AND (CAST(:categoryId AS BIGINT) IS NULL OR p.category_id = :categoryId)
                      AND (CAST(:status AS VARCHAR) IS NULL OR p.sourcing_status = :status)
                    """, params);
            case CALIBRATION -> count("""
                    SELECT COUNT(*) FROM calibration_report r
                    WHERE (CAST(:fromQuarter AS VARCHAR) IS NULL OR r.quarter >= :fromQuarter)
                      AND (CAST(:toQuarter AS VARCHAR) IS NULL OR r.quarter <= :toQuarter)
                      AND (CAST(:status AS VARCHAR) IS NULL OR r.status = :status)
                    """, params);
        };
    }

    public ReportDataset load(ReportType type, Map<String, Object> params) {
        return switch (type) {
            case WEEKLY_PICK -> weeklyPick(params);
            case SCORE_DETAIL -> scoreDetail(params);
            case ACCURACY -> accuracy(params);
            case SOURCING_QUEUE -> sourcingQueue(params);
            case CALIBRATION -> calibration(params);
        };
    }

    private ReportDataset weeklyPick(Map<String, Object> params) {
        List<ReportSection> sections = new ArrayList<>();
        for (SceneType scene : SceneType.values()) {
            String sql = """
                    SELECT p.name AS product_name, c.name AS category_name,
                           s.final_score, s.grade, s.confidence,
                           COALESCE(NULLIF(LEFT(CONCAT_WS('；',
                               CASE COALESCE(ai.content_json ->> 'action', ai.content_json ->> 'suggestion')
                                   WHEN 'ADOPT' THEN '建議：採納'
                                   WHEN 'WATCH' THEN '建議：觀察'
                                   WHEN 'REJECT' THEN '建議：不引進'
                                   ELSE NULL
                               END,
                               CASE
                                   WHEN NULLIF(ai.content_json ->> 'quantityText', '') IS NOT NULL
                                       THEN '數量：' || (ai.content_json ->> 'quantityText')
                                   WHEN NULLIF(ai.content_json ->> 'qtyMin', '') IS NOT NULL
                                       AND NULLIF(ai.content_json ->> 'qtyMax', '') IS NOT NULL
                                       THEN '數量：' || (ai.content_json ->> 'qtyMin')
                                           || '–' || (ai.content_json ->> 'qtyMax')
                                   WHEN NULLIF(ai.content_json ->> 'firstOrderQty', '') IS NOT NULL
                                       THEN '數量：' || (ai.content_json ->> 'firstOrderQty')
                                   ELSE NULL
                               END,
                               CASE WHEN NULLIF(ai.content_json ->> 'reasoning', '') IS NOT NULL
                                   THEN '理由：' || (ai.content_json ->> 'reasoning')
                                   ELSE NULL
                               END
                           ), 300), ''), '—') AS ai_summary
                    FROM product_score s
                    JOIN product p ON p.id = s.product_id
                    JOIN category c ON c.id = p.category_id
                    LEFT JOIN LATERAL (
                        SELECT i.content_json FROM ai_insight i
                        WHERE i.product_id = p.id AND i.is_current = TRUE
                          AND i.insight_type = 'RECOMMENDATION'
                        ORDER BY i.generated_at DESC LIMIT 1
                    ) ai ON TRUE
                    WHERE p.deleted_at IS NULL AND p.track_type = 'A'
                      AND s.is_active = TRUE AND s.period = :period
                      AND s.scene_type = :scene
                      AND (CAST(:categoryId AS BIGINT) IS NULL OR p.category_id = :categoryId)
                    ORDER BY s.final_score DESC, p.name ASC LIMIT 10
                    """;
            var query = jdbc.sql(sql)
                    .param("period", params.get("period"))
                    .param("scene", scene.name())
                    .param("categoryId", params.get("categoryId"));
            sections.add(new ReportSection(sceneLabel(scene), List.of(
                    col("product_name", "品項"), col("category_name", "類別"),
                    col("final_score", "總分"), col("grade", "等級"),
                    col("confidence", "信心度"), col("ai_summary", "AI 建議摘要")),
                    query.query().listOfRows()));
        }
        String riskSql = """
                SELECT p.name AS product_name, c.name AS category_name,
                       r.risk_type, r.severity, r.trigger_value, r.detected_at
                FROM risk_alert r
                JOIN product p ON p.id = r.product_id
                JOIN category c ON c.id = p.category_id
                WHERE p.deleted_at IS NULL AND r.status = 'OPEN'
                  AND (CAST(:categoryId AS BIGINT) IS NULL OR p.category_id = :categoryId)
                ORDER BY CASE r.severity WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                         r.detected_at DESC LIMIT 20
                """;
        sections.add(new ReportSection("風險示警清單", List.of(
                col("product_name", "品項"), col("category_name", "類別"),
                col("risk_type", "示警類型"), col("severity", "嚴重度"),
                col("trigger_value", "觸發值"), col("detected_at", "觸發時間")),
                jdbc.sql(riskSql).param("categoryId", params.get("categoryId"))
                        .query().listOfRows()));
        int rows = sections.stream().mapToInt(section -> section.rows().size()).sum();
        return new ReportDataset("週選品建議", params.get("period") + "・四榜各 Top 10", sections, rows);
    }

    private ReportDataset scoreDetail(Map<String, Object> params) {
        String sql = """
                SELECT s.period, p.id AS product_id, p.name AS product_name,
                       c.name AS category_name, s.scene_type, s.is_primary,
                       f.factor_code, f.raw_value, f.normalized_value,
                       f.weight,
                       CASE WHEN f.is_penalty THEN NULL
                            ELSE ROUND(f.normalized_value * f.weight, 4) END AS contribution,
                       f.penalty_value, f.is_imputed, f.data_available, f.note,
                       s.bonus_subtotal, s.penalty_subtotal, s.final_score, s.grade, s.confidence
                FROM product_score s
                JOIN product p ON p.id = s.product_id
                JOIN category c ON c.id = p.category_id
                JOIN score_factor f ON f.score_id = s.id
                WHERE p.deleted_at IS NULL AND s.is_active = TRUE
                  AND s.period = :period
                  AND (CAST(:categoryId AS BIGINT) IS NULL OR p.category_id = :categoryId)
                ORDER BY c.name, p.name, s.scene_type, f.is_penalty, f.factor_code
                """;
        List<Map<String, Object>> rows = jdbc.sql(sql)
                .param("period", params.get("period"))
                .param("categoryId", params.get("categoryId"))
                .query().listOfRows();
        List<ReportColumn> columns = List.of(
                col("period", "期別"), col("product_id", "品項 ID"), col("product_name", "品項"),
                col("category_name", "類別"), col("scene_type", "情境榜"), col("is_primary", "主情境"),
                col("factor_code", "因子"), col("raw_value", "原始值"),
                col("normalized_value", "同品類百分位"), col("weight", "權重"),
                col("contribution", "加分貢獻"), col("penalty_value", "扣分"),
                col("is_imputed", "缺值填補"), col("data_available", "有資料"), col("note", "說明"),
                col("bonus_subtotal", "加分小計"), col("penalty_subtotal", "扣分小計"),
                col("final_score", "總分"), col("grade", "等級"), col("confidence", "信心度"));
        return new ReportDataset("品項評分明細", params.get("period").toString(),
                List.of(new ReportSection("全品項六因子與加減分明細", columns, rows)), rows.size());
    }

    private ReportDataset accuracy(Map<String, Object> params) {
        Range range = range(params);
        String filters = accuracyFilters();
        String metricsSql = """
                WITH paired AS (
                    SELECT s.final_score, s.grade, cs.scene_overridden,
                           d.followed_ai, cr.actual_qty, cr.sellout_status
                    FROM decision_record d
                    JOIN product p ON p.id = d.product_id
                    JOIN product_score s ON s.id = d.score_id
                    JOIN campaign_snapshot cs ON cs.decision_id = d.id
                    JOIN campaign_result cr ON cr.decision_id = d.id
                    WHERE p.deleted_at IS NULL AND d.decided_at >= :from AND d.decided_at < :to
                """ + filters + """
                )
                SELECT COUNT(*) AS sample_size,
                       CORR(final_score, actual_qty) AS score_result_correlation,
                       AVG(CASE WHEN grade = 'A' THEN CASE WHEN sellout_status IN ('EARLY_SELLOUT','ON_TIME') THEN 1.0 ELSE 0.0 END END) AS grade_a_hit_rate,
                       AVG(CASE WHEN scene_overridden THEN 1.0 ELSE 0.0 END) AS scene_override_rate,
                       AVG(CASE WHEN followed_ai THEN 1.0 ELSE 0.0 END) AS ai_follow_rate
                FROM paired
                """;
        List<Map<String, Object>> metrics = accuracyQuery(metricsSql, params, range).query().listOfRows();
        String trendSql = """
                SELECT TO_CHAR(DATE_TRUNC('month', d.decided_at AT TIME ZONE 'Asia/Taipei'), 'YYYY-MM') AS month,
                       COUNT(*) AS sample_size,
                       AVG(CASE WHEN s.grade = 'A' THEN CASE WHEN cr.sellout_status IN ('EARLY_SELLOUT','ON_TIME') THEN 1.0 ELSE 0.0 END END) AS grade_a_hit_rate,
                       AVG(CASE WHEN cs.scene_overridden THEN 1.0 ELSE 0.0 END) AS scene_override_rate,
                       AVG(CASE WHEN d.followed_ai THEN 1.0 ELSE 0.0 END) AS ai_follow_rate
                FROM decision_record d
                JOIN product p ON p.id = d.product_id
                JOIN product_score s ON s.id = d.score_id
                JOIN campaign_snapshot cs ON cs.decision_id = d.id
                JOIN campaign_result cr ON cr.decision_id = d.id
                WHERE p.deleted_at IS NULL AND d.decided_at >= :from AND d.decided_at < :to
                """ + filters + """
                GROUP BY DATE_TRUNC('month', d.decided_at AT TIME ZONE 'Asia/Taipei')
                ORDER BY DATE_TRUNC('month', d.decided_at AT TIME ZONE 'Asia/Taipei')
                """;
        List<Map<String, Object>> trend = accuracyQuery(trendSql, params, range).query().listOfRows();
        List<ReportSection> sections = List.of(
                new ReportSection("五項準確度指標", List.of(
                        col("sample_size", "已回填樣本數"), col("score_result_correlation", "分數與結果相關係數"),
                        col("grade_a_hit_rate", "A 級達標率"), col("scene_override_rate", "情境覆寫率"),
                        col("ai_follow_rate", "AI 建議採納率")), metrics),
                new ReportSection("月趨勢", List.of(
                        col("month", "月份"), col("sample_size", "樣本數"),
                        col("grade_a_hit_rate", "A 級達標率"), col("scene_override_rate", "情境覆寫率"),
                        col("ai_follow_rate", "AI 建議採納率")), trend));
        int sampleSize = metrics.isEmpty() ? 0 : ((Number) metrics.getFirst().get("sample_size")).intValue();
        String warning = sampleSize < 200 ? "・樣本數不足，本數據僅供觀察趨勢，不足以支持權重調整" : "";
        return new ReportDataset("決策準確度", params.get("from") + " ～ " + params.get("to") + warning,
                sections, sampleSize);
    }

    private ReportDataset sourcingQueue(Map<String, Object> params) {
        String sql = """
                SELECT p.id AS product_id, p.name AS product_name, c.name AS category_name,
                       p.sourcing_status, sc.lead_time_days, sc.time_gap_days,
                       sc.scouted_at, sc.report_generated_at,
                       LEFT(COALESCE(sc.scout_report, '—'), 500) AS scout_summary
                FROM sourcing_candidate sc
                JOIN product p ON p.id = sc.product_id
                JOIN category c ON c.id = p.category_id
                WHERE p.deleted_at IS NULL AND p.track_type = 'B'
                  AND (CAST(:categoryId AS BIGINT) IS NULL OR p.category_id = :categoryId)
                  AND (CAST(:status AS VARCHAR) IS NULL OR p.sourcing_status = :status)
                ORDER BY sc.time_gap_days ASC NULLS LAST, p.name ASC
                """;
        List<Map<String, Object>> rows = jdbc.sql(sql)
                .param("categoryId", params.get("categoryId"))
                .param("status", params.get("status"))
                .query().listOfRows();
        List<ReportColumn> columns = List.of(
                col("product_id", "品項 ID"), col("product_name", "候選品項"),
                col("category_name", "類別"), col("sourcing_status", "尋源狀態"),
                col("lead_time_days", "前置期（天）"), col("time_gap_days", "時效落差（天）"),
                col("scouted_at", "探索時間"), col("report_generated_at", "報告時間"),
                col("scout_summary", "探索摘要"));
        return new ReportDataset("尋源優先序", "B 軌候選清單・依時效落差排序",
                List.of(new ReportSection("B 軌候選", columns, rows)), rows.size());
    }

    private ReportDataset calibration(Map<String, Object> params) {
        String sql = """
                SELECT r.quarter, r.sample_size, r.status,
                       CASE WHEN r.sample_size < 200 THEN '樣本不足，僅供觀察' ELSE '樣本充足' END AS validity,
                       COALESCE(r.ai_interpretation, '—') AS ai_interpretation,
                       r.adjustment_advice::text AS adjustment_advice,
                       r.accepted_items::text AS accepted_items,
                       COALESCE(u.display_name, '—') AS reviewed_by,
                       r.reviewed_at, r.created_at
                FROM calibration_report r
                LEFT JOIN app_user u ON u.id = r.reviewed_by
                WHERE (CAST(:fromQuarter AS VARCHAR) IS NULL OR r.quarter >= :fromQuarter)
                  AND (CAST(:toQuarter AS VARCHAR) IS NULL OR r.quarter <= :toQuarter)
                  AND (CAST(:status AS VARCHAR) IS NULL OR r.status = :status)
                ORDER BY r.quarter DESC
                """;
        List<Map<String, Object>> rows = jdbc.sql(sql)
                .param("fromQuarter", params.get("fromQuarter"))
                .param("toQuarter", params.get("toQuarter"))
                .param("status", params.get("status"))
                .query().listOfRows();
        List<ReportColumn> columns = List.of(
                col("quarter", "季度"), col("sample_size", "樣本數"), col("status", "審核狀態"),
                col("validity", "效度"), col("ai_interpretation", "AI 解讀"),
                col("adjustment_advice", "校準建議"), col("accepted_items", "核准結果"),
                col("reviewed_by", "審核人"), col("reviewed_at", "審核時間"), col("created_at", "建立時間"));
        return new ReportDataset("權重校準紀錄", "歷次校準建議與核准結果",
                List.of(new ReportSection("校準紀錄", columns, rows)), rows.size());
    }

    private int countAccuracy(Map<String, Object> params) {
        Range range = range(params);
        String sql = """
                SELECT COUNT(*) FROM decision_record d
                JOIN product p ON p.id = d.product_id
                JOIN campaign_result cr ON cr.decision_id = d.id
                WHERE p.deleted_at IS NULL AND d.decided_at >= :from AND d.decided_at < :to
                """ + accuracyFilters();
        return accuracyQuery(sql, params, range).query(Integer.class).single();
    }

    private JdbcClient.StatementSpec accuracyQuery(String sql, Map<String, Object> params, Range range) {
        return jdbc.sql(sql)
                .param("from", Timestamp.from(range.from()))
                .param("to", Timestamp.from(range.toExclusive()))
                .param("categoryId", params.get("categoryId"))
                .param("decisionMakerId", params.get("decisionMakerId"));
    }

    private String accuracyFilters() {
        return """
                  AND (CAST(:categoryId AS BIGINT) IS NULL OR p.category_id = :categoryId)
                  AND (CAST(:decisionMakerId AS BIGINT) IS NULL OR d.decided_by = :decisionMakerId)
                """;
    }

    private int count(String sql, Map<String, Object> params) {
        JdbcClient.StatementSpec query = jdbc.sql(sql);
        for (String key : List.of("period", "categoryId", "status", "fromQuarter", "toQuarter")) {
            if (sql.contains(":" + key)) {
                query = query.param(key, params.get(key));
            }
        }
        return query.query(Integer.class).single();
    }

    private Range range(Map<String, Object> params) {
        LocalDate from = LocalDate.parse(params.get("from").toString());
        LocalDate to = LocalDate.parse(params.get("to").toString());
        return new Range(from.atStartOfDay(BUSINESS_ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant());
    }

    private ReportColumn col(String key, String label) {
        return new ReportColumn(key, label);
    }

    private String sceneLabel(SceneType scene) {
        return switch (scene) {
            case VIRAL -> "話題爆款榜 Top 10";
            case FESTIVAL -> "節慶檔期榜 Top 10";
            case REPLENISHMENT -> "常態補貨榜 Top 10";
            case SEASONAL -> "季節導向榜 Top 10";
        };
    }

    private record Range(java.time.Instant from, java.time.Instant toExclusive) {}
}
