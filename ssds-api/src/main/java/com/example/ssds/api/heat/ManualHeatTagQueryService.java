package com.example.ssds.api.heat;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.heat.dto.ManualHeatTagResponse;
import com.example.ssds.api.heat.dto.ResolvePlatformResponse;
import com.example.ssds.core.domain.SocialPlatformResolver;
import com.example.ssds.infra.entity.ManualHeatTag;
import com.example.ssds.infra.repository.ManualHeatTagRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-14-1 人工熱度標記的查詢面。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManualHeatTagQueryService {

    /**
     * §8 API 表 {@code GET /heat-tags?scope=&days=} 未列出的預設值：
     * scope 省略時視同 {@code ALL}，避免多數呼叫端還要記得帶這個參數。
     */
    private static final String DEFAULT_SCOPE = "ALL";
    private static final String SCOPE_MINE = "MINE";
    private static final String SCOPE_ALL = "ALL";

    /**
     * days 省略時的預設觀察窗。標記滿 30 天（{@code HEAT_TAG_EXPIRE_DAYS}）即失效，
     * 因此「近期標記」以此為預設上限，畫面才不會撈出一堆早已不計分的舊標記。
     */
    private static final int DEFAULT_DAYS = 30;

    private final ManualHeatTagRepository manualHeatTagRepository;

    public ManualHeatTagResponse getById(Long id) {
        ManualHeatTag tag = manualHeatTagRepository.findWithDetailsById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到人工熱度標記 id=" + id));
        return ManualHeatTagMapper.toResponse(tag);
    }

    /**
     * 依品項或關鍵字列出標記，觀察時間新到舊排序。
     *
     * @param productId 與 keywordId 二擇一：品項 id
     * @param keywordId 與 productId 二擇一：關鍵字 id
     */
    public List<ManualHeatTagResponse> listByTarget(Long productId, Long keywordId) {
        if ((productId == null) == (keywordId == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "productId 與 keywordId 必須恰好指定一個");
        }
        List<ManualHeatTag> tags = productId != null
                ? manualHeatTagRepository.findByProductIdOrderByObservedAtDesc(productId)
                : manualHeatTagRepository.findByKeywordIdOrderByObservedAtDesc(keywordId);
        return tags.stream().map(ManualHeatTagMapper::toResponse).toList();
    }

    /**
     * §8 API 表 {@code GET /heat-tags?scope=&days=}「標記清單」：S-15 列表畫面的一般瀏覽，
     * 不綁定特定品項或關鍵字。
     *
     * <p>{@code productId}／{@code keywordId} 任一有帶值時，視為呼叫端要看特定標的的標記，
     * 優先於 scope／days，行為與既有的 {@link #listByTarget} 一致（向下相容既有呼叫端）；
     * 兩者都未帶值時才走 scope／days 的一般查詢。
     *
     * @param scope {@code MINE}（僅本人建立）或 {@code ALL}（全部，預設）；忽略大小寫，
     *              未帶值時視同 {@code ALL}
     * @param days  觀察窗天數，僅列出最近 N 天內觀察到的標記；未帶值時預設 30 天
     */
    public List<ManualHeatTagResponse> list(Long productId, Long keywordId, String scope, Integer days) {
        if (productId != null || keywordId != null) {
            return listByTarget(productId, keywordId);
        }

        String normalizedScope = scope == null || scope.isBlank()
                ? DEFAULT_SCOPE
                : scope.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalizedScope.equals(SCOPE_MINE) && !normalizedScope.equals(SCOPE_ALL)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "scope 僅接受 MINE 或 ALL，實際為 " + scope);
        }

        int lookbackDays = days != null ? days : DEFAULT_DAYS;
        if (lookbackDays <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "days 必須為正整數，實際為 " + days);
        }
        Instant since = Instant.now().minus(lookbackDays, ChronoUnit.DAYS);

        List<ManualHeatTag> tags = normalizedScope.equals(SCOPE_MINE)
                ? manualHeatTagRepository.findByTaggedByIdAndObservedAtAfterOrderByObservedAtDesc(
                        currentUserId(), since)
                : manualHeatTagRepository.findByObservedAtAfterOrderByObservedAtDesc(since);

        return tags.stream().map(ManualHeatTagMapper::toResponse).toList();
    }

    /** 比照 {@code ManualHeatTagCommandService}：取用 JwtAuthenticationFilter 設定的登入者 id。 */
    private Long currentUserId() {
        return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    /** AC-14-1：貼上連結即時判定平台別，供前端在送出前顯示。 */
    public ResolvePlatformResponse resolvePlatform(String sourceUrl) {
        return new ResolvePlatformResponse(SocialPlatformResolver.resolve(sourceUrl).name());
    }
}
