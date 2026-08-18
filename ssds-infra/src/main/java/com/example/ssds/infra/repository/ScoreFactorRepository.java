package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.infra.entity.ScoreFactor;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 因子明細（規格書 §7.2 score_factor）。
 *
 * <p>畫面上每根長條對應一列，因此查詢一律以 score_id 為條件整批取出，
 * 不要逐個因子查。
 */
@Repository
public interface ScoreFactorRepository extends JpaRepository<ScoreFactor, Long> {

    List<ScoreFactor> findByScoreId(Long scoreId);

    /** 加分區塊與扣分區塊在 UI 上是分開的兩塊（FR-05 區塊 C／D）。 */
    List<ScoreFactor> findByScoreIdAndPenalty(Long scoreId, boolean penalty);

    List<ScoreFactor> findByScoreIdAndFactorCode(Long scoreId, FactorCode factorCode);

    /** §5.7：缺資料的因子要在 UI 標灰底。 */
    List<ScoreFactor> findByScoreIdAndDataAvailableFalse(Long scoreId);
}
