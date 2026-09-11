package com.example.ssds.api.product.service;

import org.springframework.http.MediaType;

/** 圖片內容與正確的 HTTP 媒體類型。 */
public record ProductImageContent(
        byte[] bytes,
        MediaType mediaType
) {
}
