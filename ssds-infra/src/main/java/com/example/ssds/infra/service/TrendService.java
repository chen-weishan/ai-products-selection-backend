package com.example.ssds.infra.service;

import com.example.ssds.core.dto.TrendKeywordDetailResponse;
import com.example.ssds.core.dto.TrendSignalProjection;
import com.example.ssds.infra.dao.TrendQueryDao;
import com.example.ssds.infra.dao.projection.SourceBreakdownRow;
import com.example.ssds.infra.dao.projection.TrendCompositeSnapshot;
import com.example.ssds.infra.dao.projection.TrendPointRow;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// 趨勢分析商業邏輯層
@Service
public class TrendService {

    private final TrendKeywordRepository trendKeywordRepository;
    private final TrendQueryDao trendQueryDao;
    
    // 改為靜態常數，整個類別共用一個，效能好且不需要 Spring 注入
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public TrendService(TrendKeywordRepository trendKeywordRepository,
                        TrendQueryDao trendQueryDao) {
        this.trendKeywordRepository = trendKeywordRepository;
        this.trendQueryDao = trendQueryDao;
    }
    // 取得所有趨勢關鍵字的 7日/30日 斜率與 AI 輔助訊號
    @Transactional(readOnly = true)
    public List<TrendSignalProjection> getAllTrendSignals() {
        return trendKeywordRepository.findTrendSignals();
    }

    // 取得單一關鍵字：折線圖 + 各來源權重明細（點進去一筆後的頁面）
    @Transactional(readOnly = true)
    public TrendKeywordDetailResponse getKeywordDetail(Long keywordId, String range) {
        String keyword = trendKeywordRepository.findById(keywordId)
                .map(TrendKeyword::getKeyword)
                .orElseThrow(() -> new IllegalArgumentException("找不到關鍵字 id=" + keywordId));

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(parseRangeDays(range));
        List<TrendPointRow> points = trendQueryDao.findTrendRange(List.of(keywordId), from, to);

        TrendCompositeSnapshot snapshot = trendQueryDao.findLatestComposite(keywordId)
                .orElseThrow(() -> new IllegalArgumentException("關鍵字 id=" + keywordId + " 尚無熱度資料"));

        List<SourceBreakdownRow> sources = trendQueryDao.findSourceBreakdown(keywordId, to);
        Map<String, BigDecimal> appliedWeights = parseAppliedWeights(snapshot.appliedWeights());

        TrendKeywordDetailResponse response = new TrendKeywordDetailResponse();
        response.setKeywordId(keywordId);
        response.setKeyword(keyword);
        response.setPoints(points.stream()
                .map(r -> new TrendKeywordDetailResponse.Point(r.statDate(), r.compositeValue()))
                .toList());
        response.setHeatToday(snapshot.compositeValue());
        response.setSlope7d(snapshot.slope7d());
        response.setSlope30d(snapshot.slope30d());
        response.setStage(snapshot.stage());
        response.setStageWeeks(snapshot.stageWeeks());
        response.setEstimatedLifespanDays(snapshot.estimatedLifespanDays());
        response.setDivergenceFlag(snapshot.divergenceFlag());

        response.setSourceDetails(sources.stream()
                .map(s -> new TrendKeywordDetailResponse.SourceDetail(
                        s.sourceCode(),
                        s.percentileWithinSource(),
                        s.availability(),
                        "CATEGORY".equals(s.granularity()),
                        appliedWeights.getOrDefault(s.sourceCode(), BigDecimal.ZERO),
                        s.slope7d(),
                        s.slope30d()))
                .toList());

        return response;
    }

    private int parseRangeDays(String range) {
        if (range != null && range.endsWith("d")) {
            return Integer.parseInt(range.substring(0, range.length() - 1));
        }
        throw new IllegalArgumentException("不支援的 range 格式: " + range);
    }

    // 修正 2：使用 TypeReference 確保正確轉型為 BigDecimal，並增加防呆處理
    private Map<String, BigDecimal> parseAppliedWeights(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, BigDecimal>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("applied_weights JSON 解析失敗", e);
        }
    }
}