package com.example.ssds.api.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.HeatReading;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.entity.ManualHeatTag;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.ManualHeatTagRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ManualHeatReadingJobTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 5);
    private static final Instant EVALUATED_AT = Instant.parse("2026-10-05T04:00:00Z");

    @Test
    void reconcileCreatesExpectedReadingDeletesStaleReadingAndRestoresAvailability() {
        HeatSource source = source(SourceAvailability.UNAVAILABLE);
        ManualHeatTagRepository tags = mock(ManualHeatTagRepository.class);
        HeatReadingRepository readings = mock(HeatReadingRepository.class);
        TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        ManualHeatTag activeTag = tag((short) 5);
        HeatReading stale = HeatReading.builder()
                .source(source)
                .keyword(TrendKeyword.builder().id(8L).build())
                .readingDate(BUSINESS_DATE)
                .rawValue(BigDecimal.TEN)
                .build();
        when(tags.findDistinctKeywordIdsByObservedAtAfter(any())).thenReturn(List.of(7L));
        when(tags.findByKeywordIdOrderByObservedAtDesc(7L)).thenReturn(List.of(activeTag));
        when(tags.findDistinctProductIdsByObservedAtAfter(any())).thenReturn(List.of());
        when(readings.findBySourceIdAndReadingDate(4L, BUSINESS_DATE)).thenReturn(List.of(stale));
        when(keywords.getReferenceById(7L)).thenReturn(TrendKeyword.builder().id(7L).build());
        ManualHeatReadingJob job = job(source, tags, readings, keywords);

        ManualHeatReadingJob.ReconcileResult result = job.reconcile(BUSINESS_DATE, EVALUATED_AT);

        assertTrue(result.readingsChanged());
        assertTrue(result.availabilityChanged());
        assertEquals(java.util.Set.of(7L, 8L), result.affectedKeywordIds());
        assertEquals(SourceAvailability.AVAILABLE, source.getAvailability());
        assertEquals(EVALUATED_AT, source.getLastFetchedAt());
        verify(readings).delete(stale);
        ArgumentCaptor<HeatReading> saved = ArgumentCaptor.forClass(HeatReading.class);
        verify(readings).save(saved.capture());
        assertEquals(0, saved.getValue().getRawValue().compareTo(new BigDecimal("60.000")));
    }

    @Test
    void reconcileIsNoOpWhenReadingAndPercentileAreAlreadyCurrent() {
        HeatSource source = source(SourceAvailability.AVAILABLE);
        ManualHeatTagRepository tags = mock(ManualHeatTagRepository.class);
        HeatReadingRepository readings = mock(HeatReadingRepository.class);
        TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        ManualHeatTag activeTag = tag((short) 5);
        HeatReading existing = HeatReading.builder()
                .source(source)
                .keyword(TrendKeyword.builder().id(7L).build())
                .readingDate(BUSINESS_DATE)
                .rawValue(new BigDecimal("60.000"))
                .percentileWithinSource(new BigDecimal("100.00"))
                .build();
        when(tags.findDistinctKeywordIdsByObservedAtAfter(any())).thenReturn(List.of(7L));
        when(tags.findByKeywordIdOrderByObservedAtDesc(7L)).thenReturn(List.of(activeTag));
        when(tags.findDistinctProductIdsByObservedAtAfter(any())).thenReturn(List.of());
        when(readings.findBySourceIdAndReadingDate(4L, BUSINESS_DATE)).thenReturn(List.of(existing));
        ManualHeatReadingJob job = job(source, tags, readings, keywords);

        ManualHeatReadingJob.ReconcileResult result = job.reconcile(BUSINESS_DATE, EVALUATED_AT);

        assertFalse(result.requiresRecomposition());
        verify(readings, never()).save(any());
        verify(readings, never()).delete(any());
    }

    private static ManualHeatReadingJob job(
            HeatSource source,
            ManualHeatTagRepository tags,
            HeatReadingRepository readings,
            TrendKeywordRepository keywords) {
        HeatSourceRepository sources = mock(HeatSourceRepository.class);
        when(sources.findBySourceCode(HeatSourceCode.MANUAL)).thenReturn(Optional.of(source));
        return new ManualHeatReadingJob(
                tags,
                sources,
                readings,
                keywords,
                mock(ProductRepository.class),
                14,
                30);
    }

    private static HeatSource source(SourceAvailability availability) {
        return HeatSource.builder()
                .id(4L)
                .sourceCode(HeatSourceCode.MANUAL)
                .enabled(true)
                .availability(availability)
                .build();
    }

    private static ManualHeatTag tag(short heatLevel) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(3L);
        ManualHeatTag tag = mock(ManualHeatTag.class);
        when(tag.getHeatLevel()).thenReturn(heatLevel);
        when(tag.getObservedAt()).thenReturn(EVALUATED_AT.minusSeconds(3600));
        when(tag.getTaggedBy()).thenReturn(user);
        return tag;
    }
}
