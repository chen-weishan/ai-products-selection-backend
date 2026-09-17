package com.example.ssds.api.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;

/**
 * §FR-04 顯示內容表：情境判定「經人工覆寫者附標記」。
 *
 * <p>驗的是「哪一筆判定紀錄算數」這條規則，與資料庫無關，因此用 mock 而非 @SpringBootTest。
 */
@ExtendWith(MockitoExtension.class)
class SceneOverrideLookupTest {

    @Mock
    private SceneClassificationLogRepository sceneClassificationLogRepository;

    @InjectMocks
    private SceneOverrideLookup lookup;

    private SceneClassificationLog log(long productId, Instant createdAt, boolean overridden) {
        return SceneClassificationLog.builder()
                .product(ScoreTestFixtures.product(productId, "品項 " + productId))
                .finalSceneType(SceneType.VIRAL)
                .heatBucket("HIGH")
                .period("2026W30")
                .createdAt(createdAt)
                .overriddenBy(overridden ? AppUser.builder().id(9L).build() : null)
                .overrideReason(overridden ? "節慶檔期更貼近" : null)
                .build();
    }

    @Test
    @DisplayName("最新一筆判定為人工覆寫時列入標記")
    void latestOverrideIsFlagged() {
        when(sceneClassificationLogRepository
                .findByPeriodAndProductIdInOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(List.of(log(1L, Instant.parse("2026-07-20T00:00:00Z"), true)));

        assertThat(lookup.overriddenProductIds("2026W30", List.of(1L))).containsExactly(1L);
    }

    /**
     * 覆寫之後又重跑一次自動判定，現況就<b>不是</b>覆寫。
     *
     * <p>這正是不能只用 {@code where overridden_by is not null} 去查的原因——
     * 那種寫法會讓這個品項永遠掛著覆寫標記。
     */
    @Test
    @DisplayName("覆寫後又重新判定的品項不再標記")
    void supersededOverrideIsNotFlagged() {
        when(sceneClassificationLogRepository
                .findByPeriodAndProductIdInOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(List.of(
                        // 查詢依 createdAt 遞減，較新的那筆在前
                        log(1L, Instant.parse("2026-07-22T00:00:00Z"), false),
                        log(1L, Instant.parse("2026-07-20T00:00:00Z"), true)));

        assertThat(lookup.overriddenProductIds("2026W30", List.of(1L))).isEmpty();
    }

    /** 空集合短路：不做這件事會送出 {@code in ()} 這種不合法的 SQL。 */
    @Test
    @DisplayName("品項清單為空時不查資料庫")
    void emptyInputSkipsQuery() {
        assertThat(lookup.overriddenProductIds("2026W30", List.of())).isEmpty();

        verify(sceneClassificationLogRepository, never())
                .findByPeriodAndProductIdInOrderByCreatedAtDesc(anyString(), any());
    }
}
