package com.example.ssds.api.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ssds.api.score.DrivingTargetLookup.DrivingTargets;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.infra.entity.FestivalCalendar;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.FestivalCalendarRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;

/** AC-17-6 生效標的名稱查詢。純 Mockito，不碰資料庫。 */
@ExtendWith(MockitoExtension.class)
class DrivingTargetLookupTest {

    @Mock
    private TrendKeywordRepository trendKeywordRepository;

    @Mock
    private FestivalCalendarRepository festivalCalendarRepository;

    @InjectMocks
    private DrivingTargetLookup lookup;

    @Test
    @DisplayName("沒有任何生效標的 → 不查資料庫（避免 in ()）")
    void noIdsNoQuery() {
        DrivingTargets targets = lookup.resolve(List.of(
                ScoreFactor.builder().factorCode(FactorCode.MARGIN).build()));

        assertThat(targets.keywords()).isEmpty();
        assertThat(targets.festivals()).isEmpty();
        verify(trendKeywordRepository, never()).findAllById(any());
        verify(festivalCalendarRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("重複 id 只查一次，查到的轉成 id → 名稱")
    void resolvesDistinctIds() {
        when(trendKeywordRepository.findAllById(Set.of(9L)))
                .thenReturn(List.of(TrendKeyword.builder().id(9L).keyword("露營").build()));
        when(festivalCalendarRepository.findAllById(Set.of(7L)))
                .thenReturn(List.of(FestivalCalendar.builder().id(7L).festivalName("中秋節").build()));

        DrivingTargets targets = lookup.resolve(List.of(
                ScoreFactor.builder().factorCode(FactorCode.TREND).drivingKeywordId(9L).build(),
                ScoreFactor.builder().factorCode(FactorCode.TREND).drivingKeywordId(9L).build(),
                ScoreFactor.builder().factorCode(FactorCode.FESTIVAL).drivingFestivalId(7L).build()));

        assertThat(targets.keyword(9L)).isEqualTo("露營");
        assertThat(targets.festival(7L)).isEqualTo("中秋節");
        assertThat(targets.festival(null)).isNull();
    }
}
