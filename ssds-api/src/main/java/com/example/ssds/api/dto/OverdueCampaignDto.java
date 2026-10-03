package com.example.ssds.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverdueCampaignDto {
    /** 「去回填」直接打 POST /decisions/{id}/result 用，免前端再查一次決策 */
    private Long decisionId;
    private Long productId;
    private String productName;
    private LocalDate campaignEndDate;
    private Long overdueDays;
}
