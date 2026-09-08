package com.example.ssds.ai.client;

/** B 軌專用設定錯誤；不得在 Spring 啟動階段連帶停用 A 軌。 */
public class SourcingConfigurationException extends RuntimeException {
    public SourcingConfigurationException(String message) {
        super(message);
    }

    public SourcingConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
