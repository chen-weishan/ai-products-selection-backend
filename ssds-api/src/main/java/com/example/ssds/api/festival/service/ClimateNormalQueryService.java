package com.example.ssds.api.festival.service;

import com.example.ssds.api.festival.ClimateProperties;
import com.example.ssds.api.festival.dto.ClimateNormalResponse;
import com.example.ssds.infra.repository.ClimateNormalRepository;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 歷史同期氣候基準的唯讀查詢（S-20 標記 5、§9 API 清單）。
 *
 * <p>AC-17-4：本表是<b>歷史統計</b>，是唯一能進評分的氣候資料。短期天氣預報只做
 * 開團時機提醒，不在本模組，也不寫入 {@code product_score}。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClimateNormalQueryService {

    private final ClimateNormalRepository climateNormalRepository;
    private final ClimateProperties climateProperties;

    /**
     * 某區域的十二個月氣候基準，依月份排序。
     *
     * @param region 留空時用 {@code ssds.climate.default-region}（目前全系統單一區域，
     *               規格書 §7.2 只寫「預設 TW_TPE」，多區域是設計決定不做）
     * @return 查無資料時回空清單，不回 404——「這個區域沒有基準資料」是正常狀態
     */
    public List<ClimateNormalResponse> getByRegion(String region) {

        String regionCode = StringUtils.hasText(region) ? region : climateProperties.defaultRegion();

        return climateNormalRepository.findByRegionCodeOrderByMonthAsc(regionCode).stream()
                .map(normal -> new ClimateNormalResponse(
                        normal.getRegionCode(),
                        normal.getMonth(),
                        normal.getAvgTemp(),
                        normal.getRainProbability()))
                .toList();
    }
}
