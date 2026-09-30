package com.example.ssds.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FR-14-2：解析 Apify {@code /v2/users/me/limits} 回應。只測 {@code parse}，不連網路。
 */
class ApifyUsageProbeTest {

    @Test
    @DisplayName("正常回應：43 美元 → 4300 美分，上限 300 美元 → 30000 美分")
    void parsesUsedAndLimitIntoCents() {
        String json = """
                {"data":{"current":{"monthlyUsageUsd":43},"limits":{"maxMonthlyUsageUsd":300}}}
                """;

        Optional<ApifyUsage> usage = ApifyUsageProbe.parse(json);

        assertThat(usage).isPresent();
        assertThat(usage.get().usedCents()).isEqualTo(4300);
        assertThat(usage.get().limitCents()).isEqualTo(30000);
    }

    @Test
    @DisplayName("小數金額：0.5 美元 → 50 美分，不會被吃成 0")
    void fractionalUsdIsNotRoundedToZero() {
        String json = """
                {"data":{"current":{"monthlyUsageUsd":0.5},"limits":{"maxMonthlyUsageUsd":5}}}
                """;

        Optional<ApifyUsage> usage = ApifyUsageProbe.parse(json);

        assertThat(usage).isPresent();
        assertThat(usage.get().usedCents()).isEqualTo(50);
        assertThat(usage.get().limitCents()).isEqualTo(500);
    }

    @Test
    @DisplayName("沒有上限欄位：limitCents 為 null，已用金額照常解析")
    void missingLimitYieldsNullLimit() {
        String json = """
                {"data":{"current":{"monthlyUsageUsd":12}}}
                """;

        Optional<ApifyUsage> usage = ApifyUsageProbe.parse(json);

        assertThat(usage).isPresent();
        assertThat(usage.get().usedCents()).isEqualTo(1200);
        assertThat(usage.get().limitCents()).isNull();
    }

    @Test
    @DisplayName("沒有已用金額欄位：回空值")
    void missingUsedReturnsEmpty() {
        String json = """
                {"data":{"limits":{"maxMonthlyUsageUsd":300}}}
                """;

        assertThat(ApifyUsageProbe.parse(json)).isEmpty();
    }

    @Test
    @DisplayName("已用金額不是數字（字串）：回空值")
    void nonNumericUsedReturnsEmpty() {
        String json = """
                {"data":{"current":{"monthlyUsageUsd":"43"},"limits":{"maxMonthlyUsageUsd":300}}}
                """;

        assertThat(ApifyUsageProbe.parse(json)).isEmpty();
    }

    @Test
    @DisplayName("不是合法 JSON：回空值，不丟例外")
    void invalidJsonReturnsEmpty() {
        assertThat(ApifyUsageProbe.parse("{not json")).isEmpty();
    }

    @Test
    @DisplayName("空內容：回空值，不丟例外")
    void emptyBodyReturnsEmpty() {
        assertThat(ApifyUsageProbe.parse("")).isEmpty();
    }

    @Test
    @DisplayName("負數收斂成 0")
    void negativeAmountsAreClampedToZero() {
        String json = """
                {"data":{"current":{"monthlyUsageUsd":-5},"limits":{"maxMonthlyUsageUsd":300}}}
                """;

        Optional<ApifyUsage> usage = ApifyUsageProbe.parse(json);

        assertThat(usage).isPresent();
        assertThat(usage.get().usedCents()).isZero();
    }

    @Test
    @DisplayName("超大數值收斂成 Integer.MAX_VALUE，不溢位")
    void hugeAmountsAreClampedToIntMax() {
        String json = """
                {"data":{"current":{"monthlyUsageUsd":1000000000000},"limits":{"maxMonthlyUsageUsd":300}}}
                """;

        Optional<ApifyUsage> usage = ApifyUsageProbe.parse(json);

        assertThat(usage).isPresent();
        assertThat(usage.get().usedCents()).isEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("token 是 null 或空白：直接回空值（不發請求）")
    void blankTokenReturnsEmpty(String token) {
        assertThat(ApifyUsageProbe.fetch(token)).isEmpty();
    }
}