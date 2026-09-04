package com.example.ssds.api.product.service;

/** 評分輸入資料不足，屬於已完成但無法產生分數的結果，而非可重試的技術錯誤。 */
public class InsufficientDataException extends RuntimeException {

    public InsufficientDataException(String message) {
        super(message);
    }
}
