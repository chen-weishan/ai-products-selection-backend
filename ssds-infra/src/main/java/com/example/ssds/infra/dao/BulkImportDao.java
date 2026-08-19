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
            String audienceTag,
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
                     price, qty, impression, audience_tag, import_batch_id)
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
                ps.setString(8, row.audienceTag());
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

    /**
     * 批次寫入每日熱度。同日重跑採集時以新值覆蓋（upsert），
     * 避免採集任務重試就撞主鍵而整批失敗。
     */
    @Transactional
    public int batchUpsertTrendDaily(List<Object[]> keywordDateHeat) {
        String sql = """
                INSERT INTO trend_daily (keyword_id, stat_date, heat_value)
                VALUES (?, ?, ?)
                ON CONFLICT (keyword_id, stat_date)
                DO UPDATE SET heat_value = EXCLUDED.heat_value
                """;
        int[] counts = jdbcTemplate.batchUpdate(sql, keywordDateHeat);
        int affected = 0;
        for (int c : counts) {
            affected += (c >= 0 ? c : 1);
        }
        return affected;
    }
}
