package com.example.ssds.api.heat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.AdapterType;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.ingest.ApifyUsage;
import com.example.ssds.ingest.HeatSourceAdapter;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-14-2：把 Apify 用量寫回 heat_source 額度欄位的規則（單位：美分）。
 */
class HeatSourceQuotaTest {

    private HeatSource source(int used, Integer limit) {
        return HeatSource.builder()
                .id(1L)
                .sourceCode(HeatSourceCode.THREADS)
                .adapterType(AdapterType.REST)
                .quotaUsed(used)
                .quotaLimit(limit)
                .build();
    }

    @Test
    @DisplayName("讀到用量時是覆寫，不是累加")
    void overwritesInsteadOfAccumulating() {
        HeatSource source = source(1000, 5000);
        HeatSourceAdapter adapter = mock(HeatSourceAdapter.class);
        when(adapter.fetchQuota()).thenReturn(Optional.of(new ApifyUsage(4300, 30000)));

        boolean updated = HeatSourceQuota.refresh(source, adapter);

        assertThat(updated).isTrue();
        assertThat(source.getQuotaUsed()).isEqualTo(4300);
        assertThat(source.getQuotaLimit()).isEqualTo(30000);
    }

    @Test
    @DisplayName("Apify 沒有回傳上限時，把舊的上限清成 null")
    void clearsLimitWhenApifyReturnsNone() {
        HeatSource source = source(1000, 5000);
        HeatSourceAdapter adapter = mock(HeatSourceAdapter.class);
        when(adapter.fetchQuota()).thenReturn(Optional.of(new ApifyUsage(700, null)));

        boolean updated = HeatSourceQuota.refresh(source, adapter);

        assertThat(updated).isTrue();
        assertThat(source.getQuotaUsed()).isEqualTo(700);
        assertThat(source.getQuotaLimit()).isNull();
    }

    @Test
    @DisplayName("查不到用量：保留原值並回傳 false")
    void keepsExistingValuesWhenUsageUnavailable() {
        HeatSource source = source(1000, 5000);
        HeatSourceAdapter adapter = mock(HeatSourceAdapter.class);
        when(adapter.fetchQuota()).thenReturn(Optional.empty());

        boolean updated = HeatSourceQuota.refresh(source, adapter);

        assertThat(updated).isFalse();
        assertThat(source.getQuotaUsed()).isEqualTo(1000);
        assertThat(source.getQuotaLimit()).isEqualTo(5000);
    }

    @Test
    @DisplayName("adapter 丟例外：吞掉、保留原值並回傳 false")
    void swallowsAdapterExceptionAndKeepsValues() {
        HeatSource source = source(1000, 5000);
        HeatSourceAdapter adapter = mock(HeatSourceAdapter.class);
        when(adapter.fetchQuota()).thenThrow(new IllegalStateException("boom"));

        boolean updated = HeatSourceQuota.refresh(source, adapter);

        assertThat(updated).isFalse();
        assertThat(source.getQuotaUsed()).isEqualTo(1000);
        assertThat(source.getQuotaLimit()).isEqualTo(5000);
    }
}