package com.example.ssds.ai.client;

/** 外送 payload 含未核准欄位時採 fail-closed。 */
public class OutboundDataPolicyException extends RuntimeException {
    public OutboundDataPolicyException(String message) {
        super(message);
    }

    public OutboundDataPolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
