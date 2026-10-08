package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.RuntimeSetting;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuntimeSettingRepository extends JpaRepository<RuntimeSetting, String> {
    List<RuntimeSetting> findByGroupNameOrderByKey(String groupName);
}
