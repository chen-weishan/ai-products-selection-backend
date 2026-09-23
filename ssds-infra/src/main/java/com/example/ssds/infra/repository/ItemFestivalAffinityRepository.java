package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.ItemFestivalAffinity;
import com.example.ssds.infra.entity.id.ItemFestivalAffinityId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** 品項與節慶關聯度（規格書 §7.2 item_festival_affinity）。 */
@Repository
public interface ItemFestivalAffinityRepository
        extends JpaRepository<ItemFestivalAffinity, ItemFestivalAffinityId> {

    List<ItemFestivalAffinity> findByProductIdOrderByFestivalCodeAsc(Long productId);

    Optional<ItemFestivalAffinity> findByProductIdAndFestivalCode(
            Long productId, String festivalCode);

    List<ItemFestivalAffinity> findByFestivalCode(String festivalCode);

    void deleteByProductId(Long productId);

    /**
     * 一批節慶各自關聯到哪些品類（S-20 列表的「關聯品類」欄）。
     *
     * <p>不逐個節慶呼叫 {@link #findByFestivalCode(String)} 再走
     * {@code affinity.getProduct().getCategory()}：那是 1 + N + N 次查詢，
     * 一個年度 12 個檔期、每個檔期數十個品項就會炸開。這裡一次查完並在
     * 資料庫端 group by 去重，回來的每列就是一組 (節慶, 品類)。
     *
     * @param festivalCodes 不可為空集合（JPQL 的 {@code in ()} 不合法，呼叫端先擋）
     */
    @Query("""
            select a.festivalCode as festivalCode,
                   p.category.id  as categoryId
            from ItemFestivalAffinity a
            join a.product p
            where a.festivalCode in :festivalCodes
            group by a.festivalCode, p.category.id
            """)
    List<FestivalCategoryLink> findCategoryLinks(
            @Param("festivalCodes") Collection<String> festivalCodes);

    /** {@link #findCategoryLinks} 的投影：只取兩欄，不撈整個 entity。 */
    interface FestivalCategoryLink {

        String getFestivalCode();

        Long getCategoryId();
    }
}
