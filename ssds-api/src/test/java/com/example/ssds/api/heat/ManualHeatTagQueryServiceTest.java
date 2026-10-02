package com.example.ssds.api.heat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.heat.dto.ManualHeatTagResponse;
import com.example.ssds.core.domain.SocialPlatform;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.ManualHeatTag;
import com.example.ssds.infra.repository.ManualHeatTagRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * FR-14-1 標記清單查詢（§8 API 表 {@code GET /heat-tags?scope=&days=}）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManualHeatTagQueryServiceTest {

    private static final Long CURRENT_USER_ID = 100L;

    @Mock
    private ManualHeatTagRepository manualHeatTagRepository;

    private ManualHeatTagQueryService service;

    @BeforeEach
    void setUp() {
        service = new ManualHeatTagQueryService(manualHeatTagRepository);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(CURRENT_USER_ID, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ManualHeatTag sampleTag() {
        return ManualHeatTag.builder()
                .id(1L)
                .sourceUrl("https://www.facebook.com/x")
                .platform(SocialPlatform.FACEBOOK)
                .heatLevel((short) 3)
                .taggedBy(AppUser.builder().id(CURRENT_USER_ID).displayName("me").build())
                .observedAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("productId／keywordId 指定時：沿用既有的依標的查詢")
    class TargetedList {

        @Test
        @DisplayName("指定 productId 時查該品項的標記，忽略 scope／days")
        void listsByProductIdIgnoringScopeAndDays() {
            when(manualHeatTagRepository.findByProductIdOrderByObservedAtDesc(5L))
                    .thenReturn(List.of(sampleTag()));

            List<ManualHeatTagResponse> result = service.list(5L, null, "MINE", 7);

            assertThat(result).hasSize(1);
            verify(manualHeatTagRepository, never())
                    .findByObservedAtAfterOrderByObservedAtDesc(any());
        }

        @Test
        @DisplayName("productId／keywordId 皆未指定，改走 scope／days 一般查詢")
        void bothNullFallsBackToScopeQuery() {
            when(manualHeatTagRepository.findByObservedAtAfterOrderByObservedAtDesc(any()))
                    .thenReturn(List.of(sampleTag()));

            List<ManualHeatTagResponse> result = service.list(null, null, "ALL", 30);

            assertThat(result).hasSize(1);
        }
    }

    @Nested
    @DisplayName("scope／days 一般查詢")
    class ScopedList {

        @Test
        @DisplayName("scope=ALL：列出所有人的標記")
        void allScopeListsEveryonesTags() {
            when(manualHeatTagRepository.findByObservedAtAfterOrderByObservedAtDesc(any()))
                    .thenReturn(List.of(sampleTag()));

            List<ManualHeatTagResponse> result = service.list(null, null, "ALL", 14);

            assertThat(result).hasSize(1);
            verify(manualHeatTagRepository)
                    .findByObservedAtAfterOrderByObservedAtDesc(any());
            verify(manualHeatTagRepository, never())
                    .findByTaggedByIdAndObservedAtAfterOrderByObservedAtDesc(any(), any());
        }

        @Test
        @DisplayName("scope=MINE：只列本人建立的標記")
        void mineScopeListsOnlyOwnTags() {
            when(manualHeatTagRepository.findByTaggedByIdAndObservedAtAfterOrderByObservedAtDesc(
                            eq(CURRENT_USER_ID), any()))
                    .thenReturn(List.of(sampleTag()));

            List<ManualHeatTagResponse> result = service.list(null, null, "mine", 14);

            assertThat(result).hasSize(1);
            verify(manualHeatTagRepository)
                    .findByTaggedByIdAndObservedAtAfterOrderByObservedAtDesc(eq(CURRENT_USER_ID), any());
        }

        @Test
        @DisplayName("scope 未帶值時預設為 ALL")
        void nullScopeDefaultsToAll() {
            when(manualHeatTagRepository.findByObservedAtAfterOrderByObservedAtDesc(any()))
                    .thenReturn(List.of());

            service.list(null, null, null, null);

            verify(manualHeatTagRepository).findByObservedAtAfterOrderByObservedAtDesc(any());
        }

        @Test
        @DisplayName("days 未帶值時預設 30 天窗口")
        void nullDaysDefaultsToThirty() {
            when(manualHeatTagRepository.findByObservedAtAfterOrderByObservedAtDesc(any()))
                    .thenReturn(List.of());
            Instant before = Instant.now().minusSeconds(35L * 24 * 3600);

            service.list(null, null, "ALL", null);

            org.mockito.ArgumentCaptor<Instant> captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
            verify(manualHeatTagRepository).findByObservedAtAfterOrderByObservedAtDesc(captor.capture());
            // 預設 30 天窗口，since 應晚於「35 天前」但早於「29 天前」
            assertThat(captor.getValue()).isAfter(before);
            assertThat(captor.getValue()).isBefore(Instant.now().minusSeconds(29L * 24 * 3600));
        }

        @Test
        @DisplayName("scope 為無效值時拋出 VALIDATION_FAILED")
        void invalidScopeThrows() {
            assertThatThrownBy(() -> service.list(null, null, "EVERYONE", 7))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("days 為 0 或負數時拋出 VALIDATION_FAILED")
        void nonPositiveDaysThrows() {
            assertThatThrownBy(() -> service.list(null, null, "ALL", 0))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.list(null, null, "ALL", -1))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
