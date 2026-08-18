package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.Supplier;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 供應商查詢（規格書 §7.2 supplier）。 */
@Repository
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    List<Supplier> findByNameContainingIgnoreCase(String keyword);
}
