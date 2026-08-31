package com.example.ssds.api.weight;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.weight.dto.WeightVersionDetailResponse;
import com.example.ssds.api.weight.dto.WeightVersionSummaryResponse;
import com.example.ssds.infra.entity.GradeThreshold;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.GradeThresholdRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 權重版本的唯讀查詢（規格書 §FR-08、§8.2、權限列 2）。 */
@Service
@RequiredArgsConstructor
public class WeightVersionQueryService {

    private final WeightVersionRepository weightVersionRepository;
    private final GradeThresholdRepository gradeThresholdRepository;

    /**
     * 版本詳情：四組權重 + 四榜門檻（AC-08-6）。
     *
     * <p>
     * 交易必須包住整個轉換過程 —— profiles 是 LAZY，交易一關就再也讀不到。
     */
    @Transactional(readOnly = true)
    public WeightVersionDetailResponse getDetail(Long versionId) {

        WeightVersion version = weightVersionRepository.findWithProfilesById(versionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到權重版本 id=" + versionId));

        List<GradeThreshold> thresholds = gradeThresholdRepository.findByVersionId(versionId);

        return WeightVersionMapper.toDetail(version, thresholds);
    }

    @Transactional(readOnly = true)
    public Page<WeightVersionSummaryResponse> list(Pageable pageable) {
        Page<WeightVersion> page = weightVersionRepository.findAll(pageable);
        return page.map(WeightVersionMapper::toSummary);
    }

    @Transactional(readOnly = true)
    public WeightVersionDetailResponse getActive() {
        WeightVersion version = weightVersionRepository.findByIsCurrentTrue()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到生效中的權重版本"));
        List<GradeThreshold> thresholds = gradeThresholdRepository.findByVersionId(version.getId());
        return WeightVersionMapper.toDetail(version, thresholds);
    }
}
