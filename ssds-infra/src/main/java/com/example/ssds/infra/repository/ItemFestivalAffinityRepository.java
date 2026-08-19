package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.ItemFestivalAffinity;
import com.example.ssds.infra.entity.id.ItemFestivalAffinityId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 品項與節慶關聯度（規格書 §7.2 item_festival_affinity）。 */
@Repository
public interface ItemFestivalAffinityRepository
        extends JpaRepository<ItemFestivalAffinity, ItemFestivalAffinityId> {

    List<ItemFestivalAffinity> findByProductId(Long productId);

    Optional<ItemFestivalAffinity> findByProductIdAndFestivalCode(
            Long productId, String festivalCode);

    List<ItemFestivalAffinity> findByFestivalCode(String festivalCode);
}
