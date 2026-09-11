package com.example.ssds.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverdueCampaignDto {
    private Long productId;
    private String productName;
    private LocalDate campaignEndDate;
    private Long overdueDays;
}
