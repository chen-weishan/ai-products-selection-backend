package com.example.ssds.api.heat.dto;

/** S-16 畫面「不採用來源」區塊：FB／TikTok／小紅書及其排除理由（附錄 C 法律評估）。 */
public record ExcludedHeatSourceResponse(String platform, String reason) {
}
