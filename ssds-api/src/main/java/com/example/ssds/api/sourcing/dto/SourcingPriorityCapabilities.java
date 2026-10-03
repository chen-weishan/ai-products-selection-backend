package com.example.ssds.api.sourcing.dto;

/** S-17 按鈕能力由後端依候選狀態與當日合成資料統一判定。 */
public record SourcingPriorityCapabilities(
        boolean canWatch,
        boolean canPrioritize,
        String prioritizeDisabledReason) {

    public static SourcingPriorityCapabilities rawScoutResult() {
        return new SourcingPriorityCapabilities(
                true, false, "尚無每日熱度合成與時效落差資料");
    }
}
