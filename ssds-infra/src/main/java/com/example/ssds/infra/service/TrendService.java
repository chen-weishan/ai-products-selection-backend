package com.example.ssds.infra.service;

import com.example.ssds.core.dto.TrendSignalProjection;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 趨勢分析商業邏輯層
 */
@Service
public class TrendService {

    private final TrendKeywordRepository trendKeywordRepository;

    // 透過建構子注入 Repository (Spring Boot 官方推薦做法)
    public TrendService(TrendKeywordRepository trendKeywordRepository) {
        this.trendKeywordRepository = trendKeywordRepository;
    }

    /**
     * 取得所有趨勢關鍵字的 7日/30日 斜率與 AI 輔助訊號
     * * @return 包含趨勢訊號的列表
     */
    @Transactional(readOnly = true)
    public List<TrendSignalProjection> getAllTrendSignals() {
        
        return trendKeywordRepository.findTrendSignals();
    }
}   