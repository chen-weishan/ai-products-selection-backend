package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.infra.entity.ImportMappingTemplate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImportMappingTemplateRepository
        extends JpaRepository<ImportMappingTemplate, Long> {

    List<ImportMappingTemplate> findByCreatedByIdAndDataTypeOrderByUpdatedAtDesc(
            Long createdById,
            ImportDataType dataType);

    Optional<ImportMappingTemplate> findByIdAndCreatedById(Long id, Long createdById);

    boolean existsByCreatedByIdAndDataTypeAndNameIgnoreCase(
            Long createdById,
            ImportDataType dataType,
            String name);

    boolean existsByCreatedByIdAndDataTypeAndNameIgnoreCaseAndIdNot(
            Long createdById,
            ImportDataType dataType,
            String name,
            Long id);
}
