package com.example.ssds.infra.dao;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 大量匯入的批次寫入（FR-09、§7.3）。
 *
 * <p>§7.3 明訂：大量匯入使用 JDBC batch insert（batch size 500），
 * 並關閉 Hibernate 一級快取累積。用 {@code EntityManager.persist()} 逐筆寫，
 * 五萬列會在 persistence context 裡堆五萬個受管物件，記憶體與 flush 成本
 * 都是災難 —— 所以匯入路徑刻意繞開 JPA，直接下 JDBC。
 */
@Repository
public class BulkImportDao {

    /** §7.3 指定的批次大小。 */
    public static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;

    public BulkImportDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 單列銷售紀錄的匯入輸入。product/category 比對不到時傳 null。 */
    public record SalesRow(
            LocalDate orderDate,
            Long productId,
            String productNameRaw,
            Long categoryId,
            java.math.BigDecimal price,
            int qty,
            Integer impression,
            String audienceCode,
            Long importBatchId) {}

    /**
     * 批次寫入銷售紀錄。
     *
     * @return 每個批次的影響列數陣列串接後的總筆數
     */
    @Transactional
    public int batchInsertSalesRecords(List<SalesRow> rows) {
        String sql = """
                INSERT INTO sales_record
                    (order_date, product_id, product_name_raw, category_id,
                     price, qty, impression, audience_code, import_batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        int inserted = 0;
        for (int start = 0; start < rows.size(); start += BATCH_SIZE) {
            List<SalesRow> chunk = rows.subList(start, Math.min(start + BATCH_SIZE, rows.size()));
            int[][] counts = jdbcTemplate.batchUpdate(sql, chunk, chunk.size(), (ps, row) -> {
                ps.setObject(1, row.orderDate());
                ps.setObject(2, row.productId());
                ps.setString(3, row.productNameRaw());
                ps.setObject(4, row.categoryId());
                ps.setBigDecimal(5, row.price());
                ps.setInt(6, row.qty());
                ps.setObject(7, row.impression());
                ps.setString(8, row.audienceCode());
                ps.setObject(9, row.importBatchId());
            });
            for (int[] batch : counts) {
                for (int c : batch) {
                    // PostgreSQL 在 batch 模式下可能回傳 SUCCESS_NO_INFO(-2)，
                    // 此時無法得知實際列數，一律以 1 計
                    inserted += (c >= 0 ? c : 1);
                }
            }
        }
        return inserted;
    }

    /** 單列評論的匯入輸入。 */
    public record ReviewRow(
            Long productId,
            String source,
            String content,
            java.math.BigDecimal rating,
            LocalDate reviewedAt,
            String contentHash) {}

    /**
     * 批次寫入評論，重複者直接略過。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 搭配 uk_review(product_id, content_hash)：
     * 讓資料庫處理去重，比「先查再寫」少一半往返，也沒有查與寫之間的競態。
     */
    @Transactional
    public int batchInsertReviews(List<ReviewRow> rows) {
        String sql = """
                INSERT INTO product_review
                    (product_id, source, content, rating, reviewed_at, content_hash)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (product_id, content_hash) DO NOTHING
                """;

        int inserted = 0;
        for (int start = 0; start < rows.size(); start += BATCH_SIZE) {
            List<ReviewRow> chunk = rows.subList(start, Math.min(start + BATCH_SIZE, rows.size()));
            int[][] counts = jdbcTemplate.batchUpdate(sql, chunk, chunk.size(), (ps, row) -> {
                ps.setObject(1, row.productId());
                ps.setString(2, row.source());
                ps.setString(3, row.content());
                ps.setBigDecimal(4, row.rating());
                ps.setObject(5, row.reviewedAt());
                ps.setString(6, row.contentHash());
            });
            for (int[] batch : counts) {
                for (int c : batch) {
                    inserted += (c >= 0 ? c : 1);
                }
            }
        }
        return inserted;
    }

    /** 去識別化客群主檔；重複代碼由驗證層列為失敗，DB 衝突時安全略過。 */
    public record AudienceRow(
            String audienceCode,
            String name,
            java.math.BigDecimal priceMin,
            java.math.BigDecimal priceMax,
            String note) {}

    @Transactional
    public int batchInsertAudiences(List<AudienceRow> rows) {
        String sql = """
                INSERT INTO audience_segment
                    (audience_code, name, price_min, price_max, note)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (audience_code) DO NOTHING
                """;
        return count(jdbcTemplate.batchUpdate(sql, rows, BATCH_SIZE, (ps, row) -> {
            ps.setString(1, row.audienceCode());
            ps.setString(2, row.name());
            ps.setBigDecimal(3, row.priceMin());
            ps.setBigDecimal(4, row.priceMax());
            ps.setString(5, row.note());
        }));
    }

    public record AudienceMixRow(
            Long categoryId,
            Long audienceId,
            java.math.BigDecimal share) {}

    @Transactional
    public int batchUpsertAudienceMix(List<AudienceMixRow> rows) {
        String sql = """
                INSERT INTO category_audience_mix (category_id, audience_id, share)
                VALUES (?, ?, ?)
                ON CONFLICT (category_id, audience_id)
                DO UPDATE SET share = EXCLUDED.share
                """;
        return count(jdbcTemplate.batchUpdate(sql, rows, BATCH_SIZE, (ps, row) -> {
            ps.setObject(1, row.categoryId());
            ps.setObject(2, row.audienceId());
            ps.setBigDecimal(3, row.share());
        }));
    }

    /** PRODUCT 匯入一律建立草稿；後續採購狀態轉換仍由 FR-03 負責。 */
    public record ProductRow(
            String name,
            Long categoryId,
            Long supplierId,
            java.math.BigDecimal cost,
            java.math.BigDecimal suggestedPrice,
            java.math.BigDecimal marginRate,
            Integer moq,
            String season,
            String trackType,
            String logisticsCondition,
            java.math.BigDecimal idealTempMin,
            java.math.BigDecimal idealTempMax,
            Integer shelfLifeDays,
            Long createdBy) {}

    @Transactional
    public int batchInsertProducts(List<ProductRow> rows) {
        String sql = """
                INSERT INTO product
                    (name, category_id, supplier_id, cost, suggested_price, margin_rate,
                     moq, season, status, track_type, logistics_condition,
                     ideal_temp_min, ideal_temp_max, shelf_life_days, created_by,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, now(), now())
                """;
        return count(jdbcTemplate.batchUpdate(sql, rows, BATCH_SIZE, (ps, row) -> {
            ps.setString(1, row.name());
            ps.setObject(2, row.categoryId());
            ps.setObject(3, row.supplierId());
            ps.setBigDecimal(4, row.cost());
            ps.setBigDecimal(5, row.suggestedPrice());
            ps.setBigDecimal(6, row.marginRate());
            ps.setObject(7, row.moq());
            ps.setString(8, row.season());
            ps.setString(9, row.trackType());
            ps.setString(10, row.logisticsCondition());
            ps.setBigDecimal(11, row.idealTempMin());
            ps.setBigDecimal(12, row.idealTempMax());
            ps.setObject(13, row.shelfLifeDays());
            ps.setObject(14, row.createdBy());
        }));
    }

    public record ErrorRow(
            Long batchId,
            int rowNumber,
            String columnName,
            String message,
            String rawRow) {}

    @Transactional
    public int batchInsertImportErrors(List<ErrorRow> rows) {
        String sql = """
                INSERT INTO import_error
                    (batch_id, row_number, column_name, error_message, raw_row)
                VALUES (?, ?, ?, ?, ?)
                """;
        return count(jdbcTemplate.batchUpdate(sql, rows, BATCH_SIZE, (ps, row) -> {
            ps.setObject(1, row.batchId());
            ps.setInt(2, row.rowNumber());
            ps.setString(3, row.columnName());
            ps.setString(4, row.message());
            ps.setString(5, row.rawRow());
        }));
    }

    private int count(int[][] counts) {
        int affected = 0;
        for (int[] batch : counts) {
            for (int result : batch) {
                affected += result >= 0 ? result : 1;
            }
        }
        return affected;
    }

    /**
     * 批次寫入每日合成熱度（{@code heat_composite_daily}，v3.0 §7.2.3）。
     * 同日重跑合成時以新值覆蓋（upsert），避免任務重試就撞主鍵而整批失敗。
     *
     * <p>v1.0 的 trend_daily 已廢除，本方法原本寫的是那張表。
     * 參數順序：keyword_id、stat_date、composite_value、slope_7d、slope_30d、
     * stage、stage_weeks、estimated_lifespan_days、applied_weights、
     * divergence_flag、volume_below_floor。
     *
     * <p>applied_weights 一併覆寫是刻意的：它記的是「這次合成實際採用的權重」，
     * 重跑後的值就是新的那一組，留著舊的會讓事後追溯指向錯的設定。
     */
    @Transactional
    public int batchUpsertHeatComposite(List<Object[]> compositeRows) {
        String sql = """
                INSERT INTO heat_composite_daily
                    (keyword_id, stat_date, composite_value, slope_7d, slope_30d,
                     stage, stage_weeks, estimated_lifespan_days, applied_weights,
                     divergence_flag, volume_below_floor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (keyword_id, stat_date)
                DO UPDATE SET composite_value         = EXCLUDED.composite_value,
                              slope_7d                = EXCLUDED.slope_7d,
                              slope_30d               = EXCLUDED.slope_30d,
                              stage                   = EXCLUDED.stage,
                              stage_weeks             = EXCLUDED.stage_weeks,
                              estimated_lifespan_days = EXCLUDED.estimated_lifespan_days,
                              applied_weights         = EXCLUDED.applied_weights,
                              divergence_flag         = EXCLUDED.divergence_flag,
                              volume_below_floor      = EXCLUDED.volume_below_floor
                """;
        int[] counts = jdbcTemplate.batchUpdate(sql, compositeRows);
        int affected = 0;
        for (int c : counts) {
            affected += (c >= 0 ? c : 1);
        }
        return affected;
    }
}
