package com.example.ssds.core.dto;

public interface TrendSourceDetailProjection {
    String getSourceName();
    Double getPercentile();
    Double getOriginalWeight();
    Double getRawActualWeight(); // 尚未正規化，Service 層再依所有來源加總重算
    String getStatus();
    Double getSlope7d();
    Double getSlope30d();
}