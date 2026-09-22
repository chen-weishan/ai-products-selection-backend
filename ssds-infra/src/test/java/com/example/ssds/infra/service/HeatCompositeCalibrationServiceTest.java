package com.example.ssds.infra.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.HeatValueSource;
import com.example.ssds.infra.dao.TrendQueryDao;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HeatCompositeCalibrationServiceTest {

    @Test
    void recomputesRuleBaselineAndCountsConsecutiveCalendarDays() {
        TrendQueryDao queryDao = mock(TrendQueryDao.class);
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository dailyRepository = mock(HeatCompositeDailyRepository.class);
        HeatCompositeCalibrationService service =
                new HeatCompositeCalibrationService(queryDao, keywordRepository, dailyRepository);

        long keywordId = 7L;
        LocalDate date = LocalDate.of(2026, 9, 22);
        TrendKeyword keyword = TrendKeyword.builder().id(keywordId).keyword("agentic ai").build();
        HeatCompositeDaily existing = HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(date)
                .stage(HeatStage.RISING)
                .stageWeeks((short) 9)
                .stageSource(HeatValueSource.AGENT)
                .lifespanSource(HeatValueSource.AGENT)
                .build();
        List<HeatCompositeDaily> history = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(daysAgo -> HeatCompositeDaily.builder()
                        .keyword(keyword)
                        .statDate(date.minusDays(daysAgo))
                        .stage(HeatStage.PLATEAU)
                        .build())
                .toList();

        when(queryDao.findCompositeHeat(keywordId, date)).thenReturn(100.0);
        when(queryDao.findAppliedWeights(keywordId, date)).thenReturn(Map.of("google", BigDecimal.ONE));
        when(queryDao.findSlopeAnchors(keywordId, date.minusDays(1)))
                .thenReturn(Map.of("t7", new BigDecimal("110"), "t30", new BigDecimal("100")));
        when(keywordRepository.getReferenceById(keywordId)).thenReturn(keyword);
        when(dailyRepository.findByKeywordIdAndStatDate(keywordId, date)).thenReturn(Optional.of(existing));
        when(dailyRepository.findByKeywordIdAndStatDateBeforeOrderByStatDateDesc(keywordId, date))
                .thenReturn(history);
        when(dailyRepository.save(existing)).thenReturn(existing);

        HeatCompositeDaily result = service.computeAndPersist(keywordId, date).orElseThrow();

        assertSame(existing, result);
        assertEquals(HeatStage.PLATEAU, result.getStage());
        assertEquals((short) 2, result.getStageWeeks());
        assertEquals(42, result.getEstimatedLifespanDays());
        assertEquals(HeatValueSource.RULE, result.getStageSource());
        assertEquals(HeatValueSource.RULE, result.getLifespanSource());
    }
}
