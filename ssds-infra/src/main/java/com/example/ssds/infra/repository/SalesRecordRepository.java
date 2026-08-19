package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.SalesRecord;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** 銷售紀錄（規格書 §7.2 sales_record）。走 idx_sales_date_cat。 */
@Repository
public interface SalesRecordRepository extends JpaRepository<SalesRecord, Long> {

    List<SalesRecord> findByProductIdAndOrderDateBetween(
            Long productId, LocalDate from, LocalDate to);

    List<SalesRecord> findByCategoryIdAndOrderDateBetween(
            Long categoryId, LocalDate from, LocalDate to);

    /**
     * 品項歷史轉換率（conversion 因子，§5.2.1）。
     * 曝光數合計為 0 或 null 時回傳 null，由呼叫端當作「無資料」處理，
     * 不要當成轉換率 0（§5.7）。
     */
    @Query("""
            select case when coalesce(sum(s.impression), 0) = 0 then null
                        else cast(sum(s.qty) as double) / cast(sum(s.impression) as double) end
            from SalesRecord s
            where s.product.id = :productId and s.orderDate between :from and :to
            """)
    Double findConversionRate(
            @Param("productId") Long productId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    List<SalesRecord> findByImportBatchId(Long batchId);

    /** 比對不到品項的列，供後續人工對應。 */
    List<SalesRecord> findByProductIsNull();
}
