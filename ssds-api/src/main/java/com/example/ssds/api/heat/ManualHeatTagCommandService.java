package com.example.ssds.api.heat;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.heat.dto.ManualHeatTagCreateRequest;
import com.example.ssds.api.heat.dto.ManualHeatTagResponse;
import com.example.ssds.api.heat.dto.ManualHeatTagUpdateRequest;
import com.example.ssds.core.domain.RoleCode;
import com.example.ssds.core.domain.SocialPlatformResolver;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.ManualHeatTag;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.ManualHeatTagRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-14-1 人工熱度標記的新增／編輯／刪除。
 *
 * <p>「關聯品項／關鍵字」二擇一是資料庫層的 CHECK 約束（{@code manual_heat_tag}），
 * 這裡在存檔前先做一次應用層檢查，把違反規則的情況轉成 400 附清楚訊息，
 * 而不是讓它一路撞到 DataIntegrityViolationException 變成不知所云的 500。
 *
 * <p>§8 API 表對 update／delete 的權限描述是「編輯／刪除<b>自己建立的</b>標記」，
 * 不是「有權限 7 的角色都能改任何人的標記」。這裡採取的決策是：
 * 建立者本人可編輯／刪除自己的標記；SYS_ADMIN 視為系統代管角色，可代管他人標記
 * （BUYER_LEAD／DATA_ADMIN 不下放此代管權限，避免「同事誤刪同事標記」）。
 * 此決策尚待與規格書作者對齊並補進 §8，先以程式碼明確落地。
 */
@Service
@RequiredArgsConstructor
public class ManualHeatTagCommandService {

    private final ManualHeatTagRepository manualHeatTagRepository;
    private final ProductRepository productRepository;
    private final TrendKeywordRepository trendKeywordRepository;
    private final AppUserRepository appUserRepository;

    @Transactional
    public ManualHeatTagResponse create(ManualHeatTagCreateRequest request) {
        if ((request.productId() == null) == (request.keywordId() == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "productId 與 keywordId 必須恰好指定一個");
        }

        Product product = request.productId() != null ? loadProduct(request.productId()) : null;
        TrendKeyword keyword = request.keywordId() != null ? loadKeyword(request.keywordId()) : null;

        ManualHeatTag tag = ManualHeatTag.builder()
                .sourceUrl(request.sourceUrl())
                .platform(resolvePlatform(request.platform(), request.sourceUrl()))
                .heatLevel(request.heatLevel())
                .product(product)
                .keyword(keyword)
                .observedAt(request.observedAt() != null ? request.observedAt() : Instant.now())
                .taggedBy(currentUser())
                .note(request.note())
                .build();

        manualHeatTagRepository.save(tag);
        return ManualHeatTagMapper.toResponse(tag);
    }

    /**
     * 編輯既有標記。不接受變更關聯品項／關鍵字（見 DTO 註解），
     * 平台別在此明確允許覆寫成使用者手動修正的值（AC-14-1「判定錯誤時可手動修正」）。
     */
    @Transactional
    public ManualHeatTagResponse update(Long id, ManualHeatTagUpdateRequest request) {
        ManualHeatTag tag = manualHeatTagRepository.findWithDetailsById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到人工熱度標記 id=" + id));
        requireOwnerOrAdmin(tag);

        tag.setSourceUrl(request.sourceUrl());
        tag.setPlatform(request.platform());
        tag.setHeatLevel(request.heatLevel());
        if (request.observedAt() != null) {
            tag.setObservedAt(request.observedAt());
        }
        tag.setNote(request.note());

        return ManualHeatTagMapper.toResponse(tag);
    }

    @Transactional
    public void delete(Long id) {
        ManualHeatTag tag = manualHeatTagRepository.findWithDetailsById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到人工熱度標記 id=" + id));
        requireOwnerOrAdmin(tag);
        manualHeatTagRepository.delete(tag);
    }

    private Product loadProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到品項 id=" + productId));
    }

    private TrendKeyword loadKeyword(Long keywordId) {
        return trendKeywordRepository.findById(keywordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到關鍵字 id=" + keywordId));
    }

    private com.example.ssds.core.domain.SocialPlatform resolvePlatform(
            com.example.ssds.core.domain.SocialPlatform explicit, String sourceUrl) {
        return explicit != null ? explicit : SocialPlatformResolver.resolve(sourceUrl);
    }

    /**
     * {@link com.example.ssds.api.security.JwtAuthenticationFilter} 把 principal 設為
     * {@code JwtTokenProvider.userId(claims)}（{@link Long}），此處直接取用。
     * {@code @PreAuthorize("isAuthenticated()")} 已在 controller 層擋掉未登入請求，
     * 走到這裡 principal 必為非 null 的 Long。
     */
    private AppUser currentUser() {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return appUserRepository.getReferenceById(userId);
    }

    /**
     * §8「編輯／刪除自己建立的標記」的擁有者檢查。SYS_ADMIN 可代管他人標記，
     * 其餘角色（即便有權限 7、能建立標記）都只能動自己建立的那些。
     */
    private void requireOwnerOrAdmin(ManualHeatTag tag) {
        Long currentUserId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        boolean isOwner = tag.getTaggedBy() != null && tag.getTaggedBy().getId().equals(currentUserId);
        if (isOwner) {
            return;
        }
        boolean isSysAdmin = appUserRepository.getReferenceById(currentUserId).getRoles().stream()
                .anyMatch(role -> role.getCode() == RoleCode.SYS_ADMIN);
        if (!isSysAdmin) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能編輯或刪除自己建立的人工熱度標記");
        }
    }
}