package com.example.ssds.api.calibration.dto;

/** Manual Agent 7 task options; statistics are loaded from authoritative database records. */
public record WeightCalibrationTaskRequest(boolean forceRefresh) {}
