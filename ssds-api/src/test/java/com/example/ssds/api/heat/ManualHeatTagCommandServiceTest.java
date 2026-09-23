package com.example.ssds.api.heat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.heat.dto.ManualHeatTagCreateRequest;
import com.example.ssds.api.heat.dto.ManualHeatTagResponse;
import com.example.ssds.api.heat.dto.ManualHeatTagUpdateRequest;
import com.example.ssds.core.domain.RoleCode;
import com.example.ssds.core.domain.SocialPlatform;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.ManualHeatTag;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.Role;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.ManualHeatTagRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * FR-14-1 人工熱度標記新增／編輯／刪除的服務層規則
 * （AC-14-1「貼上連結自動判定平台別」、§8「編輯／刪除自己建立的標記」）。
 *
 * <p>刻意用 Mockito 而非 @SpringBootTest：這裡驗的是「二擇一必填」「擁有者檢查」等
 * 應用層業務規則，不需要真的資料庫（比照 {@code WeightVersionCommandServiceTest} 慣例）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManualHeatTagCommandServiceTest {

    private static final Long CURRENT_USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;

    @Mock
    private ManualHeatTagRepository manualHeatTagRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private TrendKeywordRepository trendKeywordRepository;

    @Mock
    private AppUserRepository appUserRepository;

    private ManualHeatTagCommandService service;

    @BeforeEach
    void setUp() {
        service = new ManualHeatTagCommandService(
                manualHeatTagRepository, productRepository, trendKeywordRepository, appUserRepository);
        loginAs(CURRENT_USER_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long userId) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null));
    }

    private AppUser userWithRoles(Long id, RoleCode... roleCodes) {
        Set<Role> roles = new java.util.LinkedHashSet<>();
        for (RoleCode code : roleCodes) {
            roles.add(Role.builder().code(code).name(code.name()).build());
        }
        return AppUser.builder().id(id).displayName("user-" + id).roles(roles).build();
    }

    @Nested
    @DisplayName("create：品項／關鍵字二擇一")
    class Create {

        @Test
        @DisplayName("兩者皆未指定時拋出 VALIDATION_FAILED")
        void rejectsWhenNeitherProductNorKeywordGiven() {
            ManualHeatTagCreateRequest request = new ManualHeatTagCreateRequest(
                    "https://www.facebook.com/some/post", null, (short) 3, null, null, null, null);

            assertThatThrownBy(() -> service.create(request)).isInstanceOf(BusinessException.class);
            verify(manualHeatTagRepository, never()).save(any());
        }

        @Test
        @DisplayName("兩者皆指定時拋出 VALIDATION_FAILED")
        void rejectsWhenBothProductAndKeywordGiven() {
            ManualHeatTagCreateRequest request = new ManualHeatTagCreateRequest(
                    "https://www.facebook.com/some/post", null, (short) 3, 1L, 2L, null, null);

            assertThatThrownBy(() -> service.create(request)).isInstanceOf(BusinessException.class);
            verify(manualHeatTagRepository, never()).save(any());
        }

        @Test
        @DisplayName("只指定 keywordId 時可正常建立，且未帶 platform 時由 URL 自動解析（AC-14-1）")
        void createsWithAutoResolvedPlatform() {
            TrendKeyword keyword = TrendKeyword.builder().id(9L).keyword("月餅").build();
            when(trendKeywordRepository.findById(9L)).thenReturn(Optional.of(keyword));
            when(appUserRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(userWithRoles(CURRENT_USER_ID));

            ManualHeatTagCreateRequest request = new ManualHeatTagCreateRequest(
                    "https://www.tiktok.com/@someone/video/123", null, (short) 4, null, 9L, null, "備註");

            ManualHeatTagResponse response = service.create(request);

            ArgumentCaptor<ManualHeatTag> captor = ArgumentCaptor.forClass(ManualHeatTag.class);
            verify(manualHeatTagRepository).save(captor.capture());
            assertThat(captor.getValue().getPlatform()).isEqualTo(SocialPlatform.TIKTOK);
            assertThat(captor.getValue().getHeatLevel()).isEqualTo((short) 4);
            assertThat(response.platform()).isEqualTo(SocialPlatform.TIKTOK.name());
        }

        @Test
        @DisplayName("明確指定 platform 時優先於自動解析")
        void explicitPlatformOverridesAutoResolve() {
            Product product = Product.builder().id(5L).name("測試品項").build();
            when(productRepository.findById(5L)).thenReturn(Optional.of(product));
            when(appUserRepository.getReferenceById(CURRENT_USER_ID)).thenReturn(userWithRoles(CURRENT_USER_ID));

            ManualHeatTagCreateRequest request = new ManualHeatTagCreateRequest(
                    "https://www.tiktok.com/@someone/video/123",
                    SocialPlatform.OTHER,
                    (short) 2,
                    5L,
                    null,
                    Instant.parse("2026-09-01T00:00:00Z"),
                    null);

            service.create(request);

            ArgumentCaptor<ManualHeatTag> captor = ArgumentCaptor.forClass(ManualHeatTag.class);
            verify(manualHeatTagRepository).save(captor.capture());
            assertThat(captor.getValue().getPlatform()).isEqualTo(SocialPlatform.OTHER);
        }

        @Test
        @DisplayName("找不到指定的品項時拋出 RESOURCE_NOT_FOUND")
        void throwsWhenProductNotFound() {
            when(productRepository.findById(999L)).thenReturn(Optional.empty());
            ManualHeatTagCreateRequest request = new ManualHeatTagCreateRequest(
                    "https://www.facebook.com/x", null, (short) 3, 999L, null, null, null);

            assertThatThrownBy(() -> service.create(request)).isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("update／delete：擁有者檢查（§8）")
    class OwnershipChecks {

        private ManualHeatTag tagOwnedByOther() {
            AppUser owner = userWithRoles(OTHER_USER_ID);
            return ManualHeatTag.builder()
                    .id(1L)
                    .sourceUrl("https://www.facebook.com/x")
                    .platform(SocialPlatform.FACEBOOK)
                    .heatLevel((short) 3)
                    .taggedBy(owner)
                    .observedAt(Instant.now())
                    .build();
        }

        @Test
        @DisplayName("本人建立的標記可自行編輯")
        void ownerCanUpdateOwnTag() {
            AppUser me = userWithRoles(CURRENT_USER_ID);
            ManualHeatTag tag = ManualHeatTag.builder()
                    .id(1L)
                    .sourceUrl("https://www.facebook.com/x")
                    .platform(SocialPlatform.FACEBOOK)
                    .heatLevel((short) 3)
                    .taggedBy(me)
                    .observedAt(Instant.now())
                    .build();
            when(manualHeatTagRepository.findWithDetailsById(1L)).thenReturn(Optional.of(tag));

            ManualHeatTagUpdateRequest request = new ManualHeatTagUpdateRequest(
                    "https://www.facebook.com/y", SocialPlatform.FACEBOOK, (short) 5, null, "改過的備註");

            ManualHeatTagResponse response = service.update(1L, request);

            assertThat(response.heatLevel()).isEqualTo((short) 5);
            assertThat(tag.getSourceUrl()).isEqualTo("https://www.facebook.com/y");
        }

        @Test
        @DisplayName("非本人、非 SYS_ADMIN 編輯他人標記時拋出 FORBIDDEN")
        void nonOwnerNonAdminCannotUpdate() {
            ManualHeatTag tag = tagOwnedByOther();
            when(manualHeatTagRepository.findWithDetailsById(1L)).thenReturn(Optional.of(tag));
            when(appUserRepository.getReferenceById(CURRENT_USER_ID))
                    .thenReturn(userWithRoles(CURRENT_USER_ID, RoleCode.BUYER));

            ManualHeatTagUpdateRequest request = new ManualHeatTagUpdateRequest(
                    "https://www.facebook.com/y", SocialPlatform.FACEBOOK, (short) 5, null, null);

            assertThatThrownBy(() -> service.update(1L, request)).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("SYS_ADMIN 可代管他人標記")
        void sysAdminCanUpdateOthersTag() {
            ManualHeatTag tag = tagOwnedByOther();
            when(manualHeatTagRepository.findWithDetailsById(1L)).thenReturn(Optional.of(tag));
            when(appUserRepository.getReferenceById(CURRENT_USER_ID))
                    .thenReturn(userWithRoles(CURRENT_USER_ID, RoleCode.SYS_ADMIN));

            ManualHeatTagUpdateRequest request = new ManualHeatTagUpdateRequest(
                    "https://www.facebook.com/y", SocialPlatform.FACEBOOK, (short) 5, null, null);

            ManualHeatTagResponse response = service.update(1L, request);
            assertThat(response.heatLevel()).isEqualTo((short) 5);
        }

        @Test
        @DisplayName("BUYER_LEAD／DATA_ADMIN 不下放代管權限，仍不可改他人標記")
        void buyerLeadCannotUpdateOthersTag() {
            ManualHeatTag tag = tagOwnedByOther();
            when(manualHeatTagRepository.findWithDetailsById(1L)).thenReturn(Optional.of(tag));
            when(appUserRepository.getReferenceById(CURRENT_USER_ID))
                    .thenReturn(userWithRoles(CURRENT_USER_ID, RoleCode.BUYER_LEAD));

            ManualHeatTagUpdateRequest request = new ManualHeatTagUpdateRequest(
                    "https://www.facebook.com/y", SocialPlatform.FACEBOOK, (short) 5, null, null);

            assertThatThrownBy(() -> service.update(1L, request)).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("本人可刪除自己建立的標記")
        void ownerCanDeleteOwnTag() {
            AppUser me = userWithRoles(CURRENT_USER_ID);
            ManualHeatTag tag = ManualHeatTag.builder().id(1L).taggedBy(me).build();
            when(manualHeatTagRepository.findWithDetailsById(1L)).thenReturn(Optional.of(tag));

            service.delete(1L);

            verify(manualHeatTagRepository).delete(tag);
        }

        @Test
        @DisplayName("非本人、非 SYS_ADMIN 刪除他人標記時拋出例外，且不會呼叫 delete")
        void nonOwnerNonAdminCannotDelete() {
            ManualHeatTag tag = tagOwnedByOther();
            when(manualHeatTagRepository.findWithDetailsById(1L)).thenReturn(Optional.of(tag));
            when(appUserRepository.getReferenceById(CURRENT_USER_ID))
                    .thenReturn(userWithRoles(CURRENT_USER_ID, RoleCode.DATA_ADMIN));

            assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(BusinessException.class);
            verify(manualHeatTagRepository, never()).delete(any());
        }
    }
}
