package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.Product;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 品項查詢（規格書 §7.2 product、FR-03）。
 *
 * <p>繼承 {@code JpaSpecificationExecutor} 是為了 FR-03-1 的多條件篩選：
 * 類別、狀態、分級、關鍵字可任意組合，若每種組合都寫一個衍生查詢方法，
 * 方法數會爆炸；Specification 讓條件在執行期組裝。
 *
 * <p>§7.3 的 N+1 規範：凡是會回傳多筆並在畫面上顯示類別／供應商名稱的查詢，
 * 一律掛 {@code @EntityGraph} 一次帶出來，不靠 lazy loading。
 */
@Repository
public interface ProductRepository
        extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    /** 品項清單：類別與供應商同時取出，避免逐列觸發 lazy 查詢。 */
    @EntityGraph(attributePaths = {"category", "supplier"})
    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "supplier"})
    Page<Product> findByCategoryIdAndStatus(Long categoryId, ProductStatus status, Pageable pageable);

    /** 品項詳情：連關鍵字一起帶，FR-05 的趨勢區塊需要。 */
    @EntityGraph(attributePaths = {"category", "supplier", "keywords"})
    Optional<Product> findWithDetailsById(Long id);

    /** FR-03-1 名稱模糊搜尋，走 idx_product_name。 */
    @EntityGraph(attributePaths = {"category"})
    Page<Product> findByNameContainingIgnoreCase(String keyword, Pageable pageable);

    /**
     * 每週全量重新評分的取件範圍（§5.10）。
     * B 軌不產生選品分數（AC-16-2），所以只取 A 軌且非草稿／已淘汰者。
     */
    @Query("""
            select p from Product p
            where p.trackType = :trackType
              and p.status not in (com.example.ssds.core.domain.ProductStatus.DRAFT,
                                   com.example.ssds.core.domain.ProductStatus.REJECTED)
            """)
    List<Product> findScorable(@Param("trackType") TrackType trackType);

    /** B 軌尋源清單（FR-16-2）。 */
    @EntityGraph(attributePaths = {"category"})
    List<Product> findByTrackTypeAndSourcingStatus(TrackType trackType, SourcingStatus sourcingStatus);

    /** §5.3.1 判斷同品類樣本數是否達 10 筆，未達則退回全品類百分位並降低信心度。 */
    long countByCategoryIdAndTrackType(Long categoryId, TrackType trackType);

    /**
     * FR-03-2 例外條件：同類別同名品項「警告但允許儲存」。
     *
     * <p>因為是<b>警告</b>而非阻擋，這件事刻意不做成資料庫唯一鍵 ——
     * 存檔前由 Service 呼叫本方法，命中就回警告讓使用者自行決定，
     * 而不是把 DataIntegrityViolationException 翻譯成錯誤訊息。
     */
    boolean existsByCategoryIdAndNameIgnoreCase(Long categoryId, String name);

    /**
     * 同上，但排除指定 id。編輯既有品項時必須用這一支，
     * 否則永遠會警告「與自己同名」。新增情境傳 -1 即可。
     */
    @Query("select count(p) > 0 from Product p "
            + "where p.category.id = :categoryId "
            + "  and lower(p.name) = lower(:name) "
            + "  and p.id <> :excludeId")
    boolean existsDuplicateName(
            @Param("categoryId") Long categoryId,
            @Param("name") String name,
            @Param("excludeId") Long excludeId);

    /** 資料匯入完成後，重新評分受影響的品項（AC-09-3）。 */
    @Query("select distinct s.product.id from SalesRecord s where s.importBatch.id = :batchId "
            + "and s.product is not null")
    List<Long> findProductIdsByImportBatch(@Param("batchId") Long batchId);
}
