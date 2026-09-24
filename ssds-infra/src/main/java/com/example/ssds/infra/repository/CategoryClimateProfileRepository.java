package com.example.ssds.infra.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ssds.infra.entity.CategoryClimateProfile;

public interface CategoryClimateProfileRepository extends JpaRepository<CategoryClimateProfile, Long> {
    List<CategoryClimateProfile> findByCategoryIdIn(Collection<Long> categoryIdLongs);
}
