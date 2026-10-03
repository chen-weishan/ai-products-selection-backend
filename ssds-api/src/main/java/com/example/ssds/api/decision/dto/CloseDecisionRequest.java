package com.example.ssds.api.decision.dto;

import java.time.LocalDate;

/**
 * 標記結案（規格書 §FR-11-2、§8.2 POST /decisions/{id}/close）。
 *
 * @param campaignEndDate 結案日期；省略時為今日（Asia/Taipei），可回填過去日期，不可為未來
 */
public record CloseDecisionRequest(LocalDate campaignEndDate) {
}
