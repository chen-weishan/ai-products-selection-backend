package com.example.ssds.api.heat;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.heat.dto.ExcludedHeatSourceResponse;
import com.example.ssds.api.heat.dto.HeatSourceDetailResponse;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.repository.HeatSourceRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** S-16 熱度來源狀態查詢（規格書 FR-14-2）。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HeatSourceQueryService {

    /**
     * 附錄 C 法律評估結論：三者均無合法的程式化資料管道
     * （服務條款明文禁止自動化擷取，且台灣已有爬蟲擷取行為構成刑法第 359 條的判決前例），
     * 改由 FR-14-1 人工熱度標記涵蓋。畫面固定顯示，非資料庫維護的內容。
     */
    private static final List<ExcludedHeatSourceResponse> EXCLUDED_SOURCES = List.of(
            new ExcludedHeatSourceResponse("FACEBOOK",
                    "無合法的程式化資料管道，服務條款禁止自動化擷取，改由人工熱度標記涵蓋（附錄 C）"),
            new ExcludedHeatSourceResponse("TIKTOK",
                    "無合法的程式化資料管道，服務條款禁止自動化擷取，改由人工熱度標記涵蓋（附錄 C）"),
            new ExcludedHeatSourceResponse("XIAOHONGSHU",
                    "無合法的程式化資料管道，服務條款禁止自動化擷取，改由人工熱度標記涵蓋（附錄 C）"));

    private final HeatSourceRepository heatSourceRepository;

    public HeatSourceDetailResponse getById(Long id) {
        HeatSource source = heatSourceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到熱度來源 id=" + id));
        return HeatSourceMapper.toDetail(source);
    }

    public List<HeatSourceDetailResponse> list() {
        return heatSourceRepository.findAll().stream().map(HeatSourceMapper::toDetail).toList();
    }

    /** 畫面「不採用來源」區塊，固定內容，不查資料庫。 */
    public List<ExcludedHeatSourceResponse> excludedSources() {
        return EXCLUDED_SOURCES;
    }
}
