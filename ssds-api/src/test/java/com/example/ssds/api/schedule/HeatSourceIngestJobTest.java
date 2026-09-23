package com.example.ssds.api.schedule;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.entity.InstagramHashtagMapping;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.HeatReadingRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.InstagramHashtagMappingRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import com.example.ssds.ingest.GoogleTrends.GoogleTrendsHeatSourceAdapter;
import com.example.ssds.ingest.Instagram.InstagramHeatSourceAdapter;
import com.example.ssds.ingest.Threads.ThreadsHeatSourceAdapter;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class HeatSourceIngestJobTest {

    @Test
    void sourceJobsRemainEnabledByDefault() {
        assertAll(
                () -> assertSourceSwitch(
                        ThreadsHeatIngestJob.class, "ssds.ingest.threads.enabled"),
                () -> assertSourceSwitch(
                        GoogleTrendsHeatIngestJob.class, "ssds.ingest.google-trends.enabled"),
                () -> assertSourceSwitch(
                        InstagramHeatIngestJob.class, "ssds.ingest.instagram.enabled"));
    }

    @Test
    void threadsFailureMarksSourceUnavailable() {
        HeatSource source = enabledSource(HeatSourceCode.THREADS);
        HeatSourceRepository sources = sourceRepository(source);
        TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        ThreadsHeatSourceAdapter adapter = mock(ThreadsHeatSourceAdapter.class);
        when(keywords.findByEnabledTrue()).thenReturn(List.of(enabledKeyword()));
        when(adapter.fetch(anyList(), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("upstream unavailable"));
        ThreadsHeatIngestJob job = new ThreadsHeatIngestJob(
                keywords, sources, mock(HeatReadingRepository.class), adapter);

        job.run();

        assertEquals(SourceAvailability.UNAVAILABLE, source.getAvailability());
        verify(sources).save(source);
    }

    @Test
    void googleTrendsFailureMarksSourceUnavailable() {
        HeatSource source = enabledSource(HeatSourceCode.GOOGLE_TRENDS);
        HeatSourceRepository sources = sourceRepository(source);
        TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        GoogleTrendsHeatSourceAdapter adapter = mock(GoogleTrendsHeatSourceAdapter.class);
        when(keywords.findByEnabledTrue()).thenReturn(List.of(enabledKeyword()));
        when(adapter.fetch(anyList(), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("upstream unavailable"));
        GoogleTrendsHeatIngestJob job = new GoogleTrendsHeatIngestJob(
                keywords, sources, mock(HeatReadingRepository.class), adapter);

        job.run();

        assertEquals(SourceAvailability.UNAVAILABLE, source.getAvailability());
        verify(sources).save(source);
    }

    @Test
    void instagramFailureMarksSourceUnavailable() {
        HeatSource source = enabledSource(HeatSourceCode.INSTAGRAM);
        HeatSourceRepository sources = sourceRepository(source);
        InstagramHashtagMappingRepository mappings = mock(InstagramHashtagMappingRepository.class);
        InstagramHeatSourceAdapter adapter = mock(InstagramHeatSourceAdapter.class);
        when(mappings.findAllEnabledWithCategory()).thenReturn(List.of(
                InstagramHashtagMapping.builder()
                        .hashtag("agent5")
                        .category(Category.builder().name("測試品類").build())
                        .enabled(true)
                        .build()));
        when(adapter.fetch(anyList(), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("upstream unavailable"));
        InstagramHeatIngestJob job = new InstagramHeatIngestJob(
                sources, mock(HeatReadingRepository.class), mappings, adapter);

        job.run();

        assertEquals(SourceAvailability.UNAVAILABLE, source.getAvailability());
        verify(sources).save(source);
    }

    private static void assertSourceSwitch(Class<?> jobType, String propertyName) {
        ConditionalOnProperty condition = jobType.getAnnotation(ConditionalOnProperty.class);
        assertEquals(propertyName, condition.name()[0]);
        assertEquals("true", condition.havingValue());
        assertTrue(condition.matchIfMissing());
    }

    private static HeatSource enabledSource(HeatSourceCode sourceCode) {
        return HeatSource.builder()
                .sourceCode(sourceCode)
                .enabled(true)
                .availability(SourceAvailability.AVAILABLE)
                .build();
    }

    private static HeatSourceRepository sourceRepository(HeatSource source) {
        HeatSourceRepository repository = mock(HeatSourceRepository.class);
        when(repository.findBySourceCode(source.getSourceCode())).thenReturn(Optional.of(source));
        return repository;
    }

    private static TrendKeyword enabledKeyword() {
        return TrendKeyword.builder().keyword("agent5").enabled(true).build();
    }
}
