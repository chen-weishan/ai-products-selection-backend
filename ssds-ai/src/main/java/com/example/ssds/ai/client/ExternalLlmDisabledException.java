package com.example.ssds.ai.client;

/** 外部 LLM 依客戶或環境政策停用。 */
public class ExternalLlmDisabledException extends RuntimeException {
    public ExternalLlmDisabledException(String message) {
        super(message);
    }
}
