package com.example.ssds.api.heat.dto;

/** S-16「測試連線」操作的即時回饋，不等排程下一輪。 */
public record HeatSourceTestResponse(String sourceCode, boolean success, String availability, String message) {
}
