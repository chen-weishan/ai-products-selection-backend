package com.example.ssds.api.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.example.ssds.ingest.ApifyUsage;
import com.example.ssds.ingest.HeatDataPoint;
import com.example.ssds.ingest.GoogleTrends.GoogleTrendsHeatSourceAdapter;
import com.example.ssds.ingest.Instagram.InstagramHeatSourceAdapter;
import com.example.ssds.ingest.Threads.ThreadsHeatSourceAdapter;
import com.example.ssds.ingest.Threads.ThreadsSearchClient;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HeatSourceIngestJobTest {

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

    @Test
    void instagramFailureKeepsExistingWeeklyReadingUsableAsDegraded() {
        HeatSource source = enabledSource(HeatSourceCode.INSTAGRAM);
        HeatSourceRepository sources = sourceRepository(source);
        HeatReadingRepository readings = mock(HeatReadingRepository.class);
        InstagramHashtagMappingRepository mappings = mock(InstagramHashtagMappingRepository.class);
        InstagramHeatSourceAdapter adapter = mock(InstagramHeatSourceAdapter.class);
        when(mappings.findAllEnabledWithCategory()).thenReturn(List.of(
                InstagramHashtagMapping.builder()
                        .hashtag("agent5")
                        .category(Category.builder().name("測試品類").build())
                        .enabled(true)
                        .build()));
        when(readings.existsBySourceSourceCodeAndReadingDateBetween(
                eq(HeatSourceCode.INSTAGRAM), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(true);
        when(adapter.fetch(anyList(), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("later retry failed"));
        InstagramHeatIngestJob job = new InstagramHeatIngestJob(
                sources, readings, mappings, adapter);

        job.run();

        assertEquals(SourceAvailability.DEGRADED, source.getAvailability());
        verify(sources).save(source);
    }

    @Test
    void restoresUnavailableInstagramWhenCurrentWeekReadingExists() {
        HeatSource source = enabledSource(HeatSourceCode.INSTAGRAM);
        source.setAvailability(SourceAvailability.UNAVAILABLE);
        HeatSourceRepository sources = sourceRepository(source);
        HeatReadingRepository readings = mock(HeatReadingRepository.class);
        when(readings.existsBySourceSourceCodeAndReadingDateBetween(
                eq(HeatSourceCode.INSTAGRAM), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(true);
        InstagramHeatIngestJob job = new InstagramHeatIngestJob(
                sources,
                readings,
                mock(InstagramHashtagMappingRepository.class),
                mock(InstagramHeatSourceAdapter.class));

        boolean restored = job.restoreAvailabilityFromCurrentWeek(LocalDate.of(2026, 9, 29));

        assertTrue(restored);
        assertEquals(SourceAvailability.DEGRADED, source.getAvailability());
        verify(sources).save(source);
    }

    @Test
    void instagramWeeklyCollectionFetchesOnlyCategoriesMissingThisWeek() {
        HeatSource source = enabledSource(HeatSourceCode.INSTAGRAM);
        HeatReadingRepository readings = mock(HeatReadingRepository.class);
        InstagramHashtagMappingRepository mappings = mock(InstagramHashtagMappingRepository.class);
        InstagramHeatSourceAdapter adapter = mock(InstagramHeatSourceAdapter.class);
        Category completed = Category.builder().id(1L).name("已有資料品類").build();
        Category missing = Category.builder().id(2L).name("缺漏品類").build();
        when(mappings.findAllEnabledWithCategory()).thenReturn(List.of(
                InstagramHashtagMapping.builder()
                        .hashtag("completed")
                        .category(completed)
                        .enabled(true)
                        .build(),
                InstagramHashtagMapping.builder()
                        .hashtag("missing")
                        .category(missing)
                        .enabled(true)
                        .build()));
        when(readings.findCategoryIdsWithReadingBetween(
                HeatSourceCode.INSTAGRAM,
                LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 9, 24)))
                .thenReturn(Set.of(completed.getId()));
        when(adapter.fetch(List.of("missing"), LocalDate.of(2026, 9, 24)))
                .thenReturn(List.of());
        InstagramHeatIngestJob job = new InstagramHeatIngestJob(
                sourceRepository(source), readings, mappings, adapter);

        job.runMissingForWeek(LocalDate.of(2026, 9, 24));

        verify(adapter).fetch(List.of("missing"), LocalDate.of(2026, 9, 24));
    }

    @Test
    void instagramWeeklyCollectionSkipsExternalRequestWhenEveryCategoryIsComplete() {
        HeatSource source = enabledSource(HeatSourceCode.INSTAGRAM);
        HeatReadingRepository readings = mock(HeatReadingRepository.class);
        InstagramHashtagMappingRepository mappings = mock(InstagramHashtagMappingRepository.class);
        InstagramHeatSourceAdapter adapter = mock(InstagramHeatSourceAdapter.class);
        Category completed = Category.builder().id(1L).name("已有資料品類").build();
        when(mappings.findAllEnabledWithCategory()).thenReturn(List.of(
                InstagramHashtagMapping.builder()
                        .hashtag("completed")
                        .category(completed)
                        .enabled(true)
                        .build()));
        when(readings.findCategoryIdsWithReadingBetween(
                HeatSourceCode.INSTAGRAM,
                LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 9, 24)))
                .thenReturn(Set.of(completed.getId()));
        InstagramHeatIngestJob job = new InstagramHeatIngestJob(
                sourceRepository(source), readings, mappings, adapter);

        job.runMissingForWeek(LocalDate.of(2026, 9, 24));

        verify(adapter, never()).fetch(anyList(), any(LocalDate.class));
    }

    @Test
    void targetedCatchUpFetchesOnlyMissingThreadsKeyword() {
        HeatSource source = enabledSource(HeatSourceCode.THREADS);
        TrendKeyword missing = enabledKeyword(1L, "新增關鍵字");
        TrendKeyword completed = enabledKeyword(2L, "已有資料");
        TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        HeatReadingRepository readings = mock(HeatReadingRepository.class);
        ThreadsHeatSourceAdapter adapter = mock(ThreadsHeatSourceAdapter.class);
        when(keywords.findAllById(List.of(1L, 2L))).thenReturn(List.of(missing, completed));
        when(readings.existsByKeywordIdAndSourceSourceCodeAndReadingDate(
                        eq(2L), eq(HeatSourceCode.THREADS), any(LocalDate.class)))
                .thenReturn(true);
        when(adapter.fetch(anyList(), any(LocalDate.class))).thenReturn(List.of());
        ThreadsHeatIngestJob job = new ThreadsHeatIngestJob(
                keywords, sourceRepository(source), readings, adapter);

        job.runForKeywordIds(List.of(1L, 2L), LocalDate.of(2026, 9, 23));

        verify(adapter).fetch(eq(List.of("新增關鍵字")), any(LocalDate.class));
    }

    @Test
    void targetedCatchUpFetchesOnlyMissingGoogleTrendsKeyword() {
        HeatSource source = enabledSource(HeatSourceCode.GOOGLE_TRENDS);
        TrendKeyword missing = enabledKeyword(1L, "新增關鍵字");
        TrendKeyword completed = enabledKeyword(2L, "已有資料");
        TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        HeatReadingRepository readings = mock(HeatReadingRepository.class);
        GoogleTrendsHeatSourceAdapter adapter = mock(GoogleTrendsHeatSourceAdapter.class);
        when(keywords.findAllById(List.of(1L, 2L))).thenReturn(List.of(missing, completed));
        when(readings.existsByKeywordIdAndSourceSourceCodeAndReadingDate(
                        eq(2L), eq(HeatSourceCode.GOOGLE_TRENDS), any(LocalDate.class)))
                .thenReturn(true);
        when(adapter.fetch(anyList(), any(LocalDate.class))).thenReturn(List.of());
        GoogleTrendsHeatIngestJob job = new GoogleTrendsHeatIngestJob(
                keywords, sourceRepository(source), readings, adapter);

        job.runForKeywordIds(List.of(1L, 2L), LocalDate.of(2026, 9, 23));

        verify(adapter).fetch(eq(List.of("新增關鍵字")), any(LocalDate.class));
    }

    @Test
    void threadsSkipsFetchWhenApifyQuotaExhausted() {
        HeatSource source = enabledSource(HeatSourceCode.THREADS);
        HeatSourceRepository sources = sourceRepository(source);
        TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        ThreadsHeatSourceAdapter adapter = mock(ThreadsHeatSourceAdapter.class);
        when(keywords.findByEnabledTrue()).thenReturn(List.of(enabledKeyword()));
        when(adapter.fetchQuota()).thenReturn(Optional.of(new ApifyUsage(5000, 5000)));
        ThreadsHeatIngestJob job = new ThreadsHeatIngestJob(
                keywords, sources, mock(HeatReadingRepository.class), adapter);

        job.run();

        verify(adapter, never()).fetch(anyList(), any(LocalDate.class));
        assertEquals(SourceAvailability.UNAVAILABLE, source.getAvailability(), "採集前額度已滿，這次才標 UNAVAILABLE");
        assertTrue(source.isEnabled(), "額度用完只略過採集，不應改動使用者的 enabled 設定");
        assertEquals(5000, source.getQuotaUsed(), "應保存剛讀到的最新用量");
        verify(sources).save(source);
    }

    @Test
    void googleTrendsSkipsFetchWhenApifyQuotaExhausted() {
        HeatSource source = enabledSource(HeatSourceCode.GOOGLE_TRENDS);
        HeatSourceRepository sources = sourceRepository(source);
        TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        GoogleTrendsHeatSourceAdapter adapter = mock(GoogleTrendsHeatSourceAdapter.class);
        when(keywords.findByEnabledTrue()).thenReturn(List.of(enabledKeyword()));
        when(adapter.fetchQuota()).thenReturn(Optional.of(new ApifyUsage(5000, 5000)));
        GoogleTrendsHeatIngestJob job = new GoogleTrendsHeatIngestJob(
                keywords, sources, mock(HeatReadingRepository.class), adapter);

        job.run();

        verify(adapter, never()).fetch(anyList(), any(LocalDate.class));
        assertEquals(SourceAvailability.UNAVAILABLE, source.getAvailability(), "採集前額度已滿，這次才標 UNAVAILABLE");
    }

    @Test
    void threadsIngestWithHighQuotaEndsDegradedInsteadOfAvailable() {
        HeatSource source = enabledSource(HeatSourceCode.THREADS);
        HeatSourceRepository sources = sourceRepository(source);
        TrendKeywordRepository keywords = mock(TrendKeywordRepository.class);
        ThreadsHeatSourceAdapter adapter = mock(ThreadsHeatSourceAdapter.class);
        when(keywords.findByEnabledTrue()).thenReturn(List.of(enabledKeyword()));
        // 採集前 4000/5000（還有額度、可以採集），採集後同樣 ≥80% → 應為 DEGRADED
        when(adapter.fetchQuota()).thenReturn(Optional.of(new ApifyUsage(4000, 5000)));
        when(adapter.fetch(anyList(), any(LocalDate.class)))
                .thenReturn(List.of(new HeatDataPoint("agent5", java.math.BigDecimal.TEN)));
        ThreadsHeatIngestJob job = new ThreadsHeatIngestJob(
                keywords, sources, mock(HeatReadingRepository.class), adapter);

        job.run();

        assertEquals(SourceAvailability.DEGRADED, source.getAvailability());
    }

    @Test
    void threadsBackfillDoesNothingWhenSourceDisabled() {
        HeatSource source = enabledSource(HeatSourceCode.THREADS);
        source.setEnabled(false);
        ThreadsSearchClient client = mock(ThreadsSearchClient.class);
        ThreadsBackfillService service = new ThreadsBackfillService(
                mock(TrendKeywordRepository.class),
                sourceRepository(source),
                mock(HeatReadingRepository.class),
                client);

        service.backfillDate(LocalDate.of(2026, 9, 15));
        service.backfillRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        service.backfillKeyword(1L, LocalDate.of(2026, 9, 15));
        service.backfillRangeForKeyword(1L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        org.mockito.Mockito.verifyNoInteractions(client);
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

    private static TrendKeyword enabledKeyword(Long id, String keyword) {
        return TrendKeyword.builder().id(id).keyword(keyword).enabled(true).build();
    }
}
