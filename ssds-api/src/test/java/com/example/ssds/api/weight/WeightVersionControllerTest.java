package com.example.ssds.api.weight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.weight.dto.CreateWeightVersionRequest;
import com.example.ssds.api.weight.dto.WeightVersionDetailResponse;
import com.example.ssds.core.domain.WeightVersionStatus;

/**
 * FR-08 controller 層的回應約定（規格書 §8.2）。
 */
@ExtendWith(MockitoExtension.class)
class WeightVersionControllerTest {

    @Mock
    private WeightVersionQueryService queryService;

    @Mock
    private WeightVersionCommandService commandService;

    @InjectMocks
    private WeightVersionController controller;

    /**
     * 審查意見 3：Location 必須指向真的存在的端點。
     *
     * <p>規格書 §8.2 是端點完整清單，裡面沒有 {@code GET /weight-versions/{id}}，
     * 沿著舊的 Location 打過去會 404。版本的完整內容在 /profiles。
     */
    @Test
    @DisplayName("201 的 Location 指向 /{id}/profiles，而不是不存在的 /{id}")
    void locationPointsToAnExistingEndpoint() {
        WeightVersionDetailResponse dto = new WeightVersionDetailResponse(
                42L, "v9", "測試版本", WeightVersionStatus.DRAFT,
                false, null, null, null, null, List.of());
        when(commandService.create(any(CreateWeightVersionRequest.class))).thenReturn(dto);

        ResponseEntity<ApiResponse<WeightVersionDetailResponse>> response =
                controller.create(new CreateWeightVersionRequest("v9", "測試版本", null, List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/v1/weight-versions/42/profiles");
    }
}
