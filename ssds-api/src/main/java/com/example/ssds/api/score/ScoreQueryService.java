package com.example.ssds.api.score;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.score.dto.ScoreDeductionsResponse;
import com.example.ssds.api.score.dto.ScoreDetailResponse;
import com.example.ssds.api.score.dto.ScoreHistoryPointResponse;
import com.example.ssds.api.score.dto.ScoreRankingRowResponse;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.ScoreFactorRepository;

import lombok.RequiredArgsConstructor;

/** FR-04 選品分數排行的讀取端（規格書 §FR-04、§8.2）。 */
@Service
@RequiredArgsConstructor
public class ScoreQueryService {

    private final ProductScoreRepository productScoreRepository;
    private final ScoreFactorRepository scoreFactorRepository;

    /**
     * 排行清單。{@code scene} 或 {@code categoryId} 為 null 時該條件不生效。
     *
     * <p>
     * readOnly：本方法只讀，關掉 dirty checking 的快照可以省一份記憶體，
     * 也避免不小心改到 entity 就被自動 flush 出去。
     */
    @Transactional(readOnly = true)
    public Page<ScoreRankingRowResponse> ranking(
            String period, SceneType scene, Long categoryId, Pageable pageable) {

        Page<ProductScore> page = productScoreRepository.findRanking(period, scene, categoryId, pageable);

        // 空頁短路：不做這件事會送出 in () 這種不合法的 SQL
        if (page.isEmpty()) {
            return Page.empty(pageable);
        }

        List<Long> scoreIds = page.getContent().stream().map(ProductScore::getId).toList();

        // 一次撈完這一頁的全部因子再依 score 分組，避免逐列查詢造成 N+1。
        // f.getScore().getId() 只讀 LAZY proxy 的外鍵值，不會觸發載入
        Map<Long, List<ScoreFactor>> factorsByScoreId = scoreFactorRepository.findByScoreIdIn(scoreIds).stream()
                .collect(Collectors.groupingBy(f -> f.getScore().getId()));

        return ScoreMapper.toRankingRows(page, factorsByScoreId);
    }

    @Transactional(readOnly = true)
    public ScoreDeductionsResponse deductions(Long scoreId) {
        ProductScore score = productScoreRepository.findById(scoreId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到分數 id=" + scoreId));
        List<ScoreFactor> penaltyFactors = scoreFactorRepository.findByScoreIdAndPenalty(scoreId, true);
        return ScoreMapper.toDeductions(score, penaltyFactors);
    }

    @Transactional(readOnly = true)
    public ScoreDetailResponse snapshot(Long id, String period, SceneType scene) {
        ProductScore score = productScoreRepository.findSnapshot(id, period, scene)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "找不到品項 " + id + " 在 " + period + " 的分數"));
        List<ScoreFactor> factors = scoreFactorRepository.findByScoreId(score.getId());
        return ScoreMapper.toDetail(score, factors);
    }

    @Transactional(readOnly = true)
    public List<ScoreHistoryPointResponse> history(Long id, SceneType scene) {
        List<ProductScore> scores = productScoreRepository.findByProductIdOrderByCalculatedAtDesc(id).stream()
                .filter(s -> scene == null || s.getSceneType() == scene).toList();
        return ScoreMapper.toHistoryPoints(scores);
    }
}
