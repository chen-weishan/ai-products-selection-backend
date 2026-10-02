package com.example.ssds.infra.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.HeatValueSource;
import com.example.ssds.infra.dao.TrendQueryDao;
import com.example.ssds.infra.entity.HeatCompositeDaily;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HeatCompositeCalibrationServiceTest {

    @Test
    void sameDateRuleRerunDoesNotIncreaseStageWeeks() {
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
                .stageSource(HeatValueSource.RULE)
                .lifespanSource(HeatValueSource.RULE)
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
        when(queryDao.findCompositeSeries(keywordId, date.minusDays(34), date.minusDays(5)))
                .thenReturn(Map.of(
                        date.minusDays(7), new BigDecimal("110"),
                        date.minusDays(8), new BigDecimal("110"),
                        date.minusDays(9), new BigDecimal("110"),
                        date.minusDays(10), new BigDecimal("110"),
                        date.minusDays(30), new BigDecimal("100"),
                        date.minusDays(31), new BigDecimal("100"),
                        date.minusDays(32), new BigDecimal("100"),
                        date.minusDays(33), new BigDecimal("100")));
        when(keywordRepository.getReferenceById(keywordId)).thenReturn(keyword);
        when(dailyRepository.findByKeywordIdAndStatDate(keywordId, date)).thenReturn(Optional.of(existing));
        when(dailyRepository.findByKeywordIdAndStatDateBeforeOrderByStatDateDesc(keywordId, date))
                .thenReturn(history);
        when(dailyRepository.save(existing)).thenReturn(existing);

        HeatCompositeDaily first = service.computeAndPersist(keywordId, date).orElseThrow();
        HeatCompositeDaily result = service.computeAndPersist(keywordId, date).orElseThrow();

        assertSame(existing, result);
        assertEquals((short) 2, first.getStageWeeks());
        assertEquals(HeatStage.PLATEAU, result.getStage());
        assertEquals((short) 2, result.getStageWeeks());
        assertEquals(42, result.getEstimatedLifespanDays());
        assertEquals(HeatValueSource.RULE, result.getStageSource());
        assertEquals(HeatValueSource.RULE, result.getLifespanSource());
    }

    @Test
    void sameDateRerunPreservesExistingAgentOverride() {
        TrendQueryDao queryDao = mock(TrendQueryDao.class);
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository dailyRepository = mock(HeatCompositeDailyRepository.class);
        HeatCompositeCalibrationService service =
                new HeatCompositeCalibrationService(queryDao, keywordRepository, dailyRepository);
        long keywordId = 8L;
        LocalDate date = LocalDate.of(2026, 9, 22);
        TrendKeyword keyword = TrendKeyword.builder().id(keywordId).keyword("agent override").build();
        HeatCompositeDaily existing = HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(date)
                .stage(HeatStage.DECLINING)
                .stageWeeks((short) 3)
                .estimatedLifespanDays(17)
                .stageSource(HeatValueSource.AGENT)
                .lifespanSource(HeatValueSource.AGENT)
                .build();
        when(queryDao.findCompositeHeat(keywordId, date)).thenReturn(100.0);
        when(queryDao.findAppliedWeights(keywordId, date)).thenReturn(Map.of("google", BigDecimal.ONE));
        when(queryDao.findCompositeSeries(keywordId, date.minusDays(34), date.minusDays(5)))
                .thenReturn(Map.of());
        when(keywordRepository.getReferenceById(keywordId)).thenReturn(keyword);
        when(dailyRepository.findByKeywordIdAndStatDate(keywordId, date)).thenReturn(Optional.of(existing));
        when(dailyRepository.findByKeywordIdAndStatDateBeforeOrderByStatDateDesc(keywordId, date))
                .thenReturn(List.of());
        when(dailyRepository.save(existing)).thenReturn(existing);

        HeatCompositeDaily result = service.computeAndPersist(keywordId, date).orElseThrow();

        assertEquals(HeatStage.DECLINING, result.getStage());
        assertEquals((short) 3, result.getStageWeeks());
        assertEquals(17, result.getEstimatedLifespanDays());
        assertEquals(HeatValueSource.AGENT, result.getStageSource());
        assertEquals(HeatValueSource.AGENT, result.getLifespanSource());
    }

    // ───────────── AC-14-4：來源降級時的合成與實際比例記錄 ─────────────

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("AC-14-4：Instagram／人工不可用時，合成列記錄的是重新正規化後的實際比例（總和 1）")
    void degradedSourcesRecordRenormalizedRatios() throws Exception {
        TrendQueryDao queryDao = mock(TrendQueryDao.class);
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository dailyRepository = mock(HeatCompositeDailyRepository.class);
        HeatCompositeCalibrationService service =
                new HeatCompositeCalibrationService(queryDao, keywordRepository, dailyRepository);
        long keywordId = 11L;
        LocalDate date = LocalDate.of(2026, 9, 29);
        TrendKeyword keyword = TrendKeyword.builder().id(keywordId).keyword("degraded").build();

        Map<String, BigDecimal> renormalized = new LinkedHashMap<>();
        renormalized.put("THREADS", new BigDecimal("0.5455"));
        renormalized.put("GOOGLE_TRENDS", new BigDecimal("0.4545"));
        when(queryDao.findCompositeHeat(keywordId, date)).thenReturn(64.5);
        when(queryDao.findAppliedWeights(keywordId, date)).thenReturn(renormalized);
        when(keywordRepository.getReferenceById(keywordId)).thenReturn(keyword);
        when(dailyRepository.findByKeywordIdAndStatDate(keywordId, date)).thenReturn(Optional.empty());
        when(dailyRepository.save(any(HeatCompositeDaily.class))).thenAnswer(inv -> inv.getArgument(0));

        service.computeAndPersist(keywordId, date).orElseThrow();

        ArgumentCaptor<HeatCompositeDaily> saved = ArgumentCaptor.forClass(HeatCompositeDaily.class);
        verify(dailyRepository).save(saved.capture());
        HeatCompositeDaily row = saved.getValue();
        assertEquals(0, new BigDecimal("64.50").compareTo(row.getCompositeValue()));

        JsonNode weights = JSON.readTree(row.getAppliedWeights());
        assertEquals(2, weights.size());
        assertTrue(weights.has("THREADS") && weights.has("GOOGLE_TRENDS"));
        assertTrue(!weights.has("INSTAGRAM") && !weights.has("MANUAL"), "不可用來源不得出現在實際比例中");
        BigDecimal sum = weights.get("THREADS").decimalValue().add(weights.get("GOOGLE_TRENDS").decimalValue());
        assertEquals(0, BigDecimal.ONE.compareTo(sum));
    }

    @Test
    @DisplayName("AC-14-4／§5.7：當日所有來源都不可用或無讀值時不寫入合成列（不視為熱度 0）")
    void noUsableSourceWritesNothing() {
        TrendQueryDao queryDao = mock(TrendQueryDao.class);
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository dailyRepository = mock(HeatCompositeDailyRepository.class);
        HeatCompositeCalibrationService service =
                new HeatCompositeCalibrationService(queryDao, keywordRepository, dailyRepository);
        LocalDate date = LocalDate.of(2026, 9, 29);
        when(queryDao.findCompositeHeat(12L, date)).thenReturn(null);

        assertTrue(service.computeAndPersist(12L, date).isEmpty());

        verify(dailyRepository, never()).save(any(HeatCompositeDaily.class));
    }

    @Test
    @DisplayName("AC-14-5 的重算路徑：同日重算會用新權重覆寫合成值與 applied_weights")
    void sameDayRecomputeOverwritesValueAndAppliedWeights() throws Exception {
        TrendQueryDao queryDao = mock(TrendQueryDao.class);
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);
        HeatCompositeDailyRepository dailyRepository = mock(HeatCompositeDailyRepository.class);
        HeatCompositeCalibrationService service =
                new HeatCompositeCalibrationService(queryDao, keywordRepository, dailyRepository);
        long keywordId = 13L;
        LocalDate date = LocalDate.of(2026, 9, 29);
        TrendKeyword keyword = TrendKeyword.builder().id(keywordId).keyword("reweighted").build();
        HeatCompositeDaily existing = HeatCompositeDaily.builder()
                .keyword(keyword)
                .statDate(date)
                .compositeValue(new BigDecimal("50.00"))
                .appliedWeights("{\"THREADS\":0.3000,\"GOOGLE_TRENDS\":0.7000}")
                .stageSource(HeatValueSource.RULE)
                .lifespanSource(HeatValueSource.RULE)
                .build();
        Map<String, BigDecimal> newWeights = new LinkedHashMap<>();
        newWeights.put("THREADS", new BigDecimal("0.7000"));
        newWeights.put("GOOGLE_TRENDS", new BigDecimal("0.3000"));
        when(queryDao.findCompositeHeat(keywordId, date)).thenReturn(72.0);
        when(queryDao.findAppliedWeights(keywordId, date)).thenReturn(newWeights);
        when(keywordRepository.getReferenceById(keywordId)).thenReturn(keyword);
        when(dailyRepository.findByKeywordIdAndStatDate(keywordId, date)).thenReturn(Optional.of(existing));
        when(dailyRepository.save(existing)).thenReturn(existing);

        HeatCompositeDaily result = service.computeAndPersist(keywordId, date).orElseThrow();

        assertSame(existing, result);
        assertEquals(0, new BigDecimal("72.00").compareTo(result.getCompositeValue()));
        JsonNode weights = JSON.readTree(result.getAppliedWeights());
        assertEquals(0, new BigDecimal("0.7000").compareTo(weights.get("THREADS").decimalValue()));
        assertEquals(0, new BigDecimal("0.3000").compareTo(weights.get("GOOGLE_TRENDS").decimalValue()));
    }
}
