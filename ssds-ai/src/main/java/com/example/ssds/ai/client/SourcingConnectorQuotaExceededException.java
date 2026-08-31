package com.example.ssds.ai.client;

/** Mistral Workspace 的 Custom Connector 額度已耗盡；重試或更換模型皆無法恢復。 */
public class SourcingConnectorQuotaExceededException extends RuntimeException {
    public SourcingConnectorQuotaExceededException(Throwable cause) {
        super("B 軌尋源 Connector 額度已達上限，請於服務額度重置後再試", cause);
    }
}
