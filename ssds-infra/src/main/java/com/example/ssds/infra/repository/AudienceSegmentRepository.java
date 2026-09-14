package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.AudienceSegment;
import java.util.List;
import java.util.Optional;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AudienceSegmentRepository extends JpaRepository<AudienceSegment, Long> {
    boolean existsByAudienceCodeIgnoreCase(String audienceCode);

    List<AudienceSegment> findAllByOrderByAudienceCodeAsc();

    Optional<AudienceSegment> findByAudienceCodeIgnoreCase(String audienceCode);

    List<AudienceSegment> findByAudienceCodeIn(Collection<String> audienceCodes);
}
