package com.example.ssds.api.admin;

/** 接收 S-14 P1 營運參數，實作類別只套用自己負責的欄位。 */
public interface OperationalRuntimeConfigurable {
    void reconfigure(RuntimeSettingsService.OperationalConfig config);
}
