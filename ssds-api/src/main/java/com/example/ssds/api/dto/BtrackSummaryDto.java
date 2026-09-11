package com.example.ssds.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BtrackSummaryDto {
    private Long productId;
    private String productName;
    private String heatStage;
    private Integer timeGapDays;
    private String sourcingStatus;
}
