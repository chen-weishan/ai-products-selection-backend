package com.example.ssds.api.heat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.HeatSourceCode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HeatCompositionRecalculationListenerTest {

    private final HeatCompositionRecalculationService recalculationService =
            mock(HeatCompositionRecalculationService.class);
    private final HeatCompositionRecalculationListener listener =
            new HeatCompositionRecalculationListener(recalculationService);
    private final HeatSourceCompositionChangedEvent event =
            new HeatSourceCompositionChangedEvent(1L, HeatSourceCode.THREADS);

    @Test
    @DisplayName("收到事件時委派給重算服務")
    void delegatesToRecalculationService() {
        listener.onCompositionChanged(event);

        verify(recalculationService).recomposeAndRescore(any(LocalDate.class), any(Instant.class));
    }

    @Test
    @DisplayName("重算失敗只記錄、不往外拋（非同步執行緒沒有呼叫端可接）")
    void swallowsExceptions() {
        when(recalculationService.recomposeAndRescore(any(LocalDate.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> listener.onCompositionChanged(event)).doesNotThrowAnyException();
    }
}
