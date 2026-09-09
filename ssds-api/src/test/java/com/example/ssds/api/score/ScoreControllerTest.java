package com.example.ssds.api.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.score.dto.ScoreRankingRowResponse;
import com.example.ssds.api.score.dto.SimulateRequest;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;

/**
 * FR-04 controller 層的回應約定（規格書 §8.2）。
 */
@ExtendWith(MockitoExtension.class)
class ScoreControllerTest {

    @Mock
    private ScoreQueryService queryService;

    @Mock
    private ScoreSimulationService simulationService;

    @InjectMocks
    private ScoreController controller;

    private ScoreRankingRowResponse row(long id) {
        return new ScoreRankingRowResponse(id, id, "品項 " + id, "零食",
                SceneType.VIRAL, true, new BigDecimal("86.89"), new BigDecimal("4.00"),
                new BigDecimal("82.89"), Grade.B, 86, false, false, List.of());
    }

    /**
     * 分頁中繼資料必須完整傳到 API 封套。
     * 前端的四榜筆數是打 size=1 只讀 totalElements，這個欄位掉了畫面就沒有數字。
     */
    @Test
    @DisplayName("排行回應保留 totalElements 與 totalPages")
    void rankingKeepsPageMetadata() {
        when(queryService.ranking(any(), any(), any(), any())).thenReturn(
                new PageImpl<>(List.of(row(1L)), PageRequest.of(0, 20), 41));

        ApiResponse<PageResponse<ScoreRankingRowResponse>> response =
                controller.ranking("2026W30", SceneType.VIRAL, null, 0, 20);

        assertThat(response.data().totalElements()).isEqualTo(41);
        assertThat(response.data().totalPages()).isEqualTo(3);
        assertThat(response.data().content()).hasSize(1);
    }

    /**
     * 排序規則固定寫在 JPQL 裡，所以 controller 只該傳頁碼與每頁筆數，
     * 不能組出帶 Sort 的 Pageable 去和 JPQL 的 order by 打架。
     */
    @Test
    @DisplayName("排行傳給服務的 Pageable 不帶排序")
    void rankingPageableCarriesNoSort() {
        when(queryService.ranking(any(), any(), any(), any())).thenReturn(
                new PageImpl<>(List.of(), PageRequest.of(2, 20), 0));

        controller.ranking("2026W30", null, null, 2, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(queryService).ranking(eq("2026W30"), isNull(), isNull(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().isSorted()).isFalse();
    }

    /** 試算回的是清單不是分頁：結果不寫入資料庫，也沒有跨頁的概念。 */
    @Test
    @DisplayName("試算回清單而非分頁")
    void simulateReturnsAList() {
        SimulateRequest request = new SimulateRequest(3L, "2026W30", SceneType.VIRAL,
                null, null, null, 20);
        when(simulationService.simulate(request)).thenReturn(List.of(row(1L), row(2L)));

        ApiResponse<List<ScoreRankingRowResponse>> response = controller.simulate(request);

        assertThat(response.data()).hasSize(2);
    }
}
