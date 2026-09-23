package com.example.ssds.api.festival.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.festival.dto.ClimateNormalResponse;
import com.example.ssds.api.festival.service.ClimateNormalQueryService;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 歷史同期氣候基準（S-20 標記 5、§9 API 清單）。 */
@RestController
@RequestMapping(value = "/climate-normals", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ClimateNormalController {

    private final ClimateNormalQueryService climateNormalQueryService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ClimateNormalResponse>> getClimateNormals(
            @RequestParam(name = "region", required = false) String region) {
        return ApiResponse.success(climateNormalQueryService.getByRegion(region));
    }
}
