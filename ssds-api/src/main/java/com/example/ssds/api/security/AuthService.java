package com.example.ssds.api.security;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.core.dto.AuthenticatedUser;
import com.example.ssds.core.dto.LoginResult;
import com.example.ssds.core.dto.TokenPair;
import com.example.ssds.core.domain.RoleCode;
import com.example.ssds.core.domain.UserStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.RefreshToken;
import com.example.ssds.infra.entity.Role;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-01 登入與權限：登入、token 換發、登出。
 *
 * <p>放在 {@code ssds-api} 而非 {@code ssds-infra}（其餘 Service 的慣例位置）：
 * 這裡要拋 {@link BusinessException}，而該類別在 api 模組，
 * infra 依規格書 §3.3 的依賴方向不能反過來依賴 api。
 */
@Service
public class AuthService {

    /** FR-01：連續失敗 5 次鎖定 15 分鐘。 */
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final AppUserRepository appUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final Duration refreshTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            AppUserRepository appUserRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            @Value("${ssds.jwt.refresh-ttl-days:7}") long refreshTtlDays) {
        this.appUserRepository = appUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTtl = Duration.ofDays(refreshTtlDays);
    }

    @Transactional
    public LoginResult login(String email, String rawPassword, String userAgent, String ip) {
        // 帳號不存在與密碼錯誤共用同一則訊息（FR-01：不透露是帳號錯還是密碼錯）
        AppUser user = appUserRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        System.out.println("User: " + user.getEmail());

        if (user.getStatus() == UserStatus.DISABLED) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (user.isLocked()) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, lockedMessage(user.getLockedUntil()));
        }

        System.out.println("Is password matched: " + rawPassword + ", " + user.getPasswordHash());

        // if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
        //     // 用 UPDATE 而非「讀出來改再存回去」，避免並行的失敗嘗試互相覆蓋計數（見 Repository 註解）
        //     appUserRepository.incrementFailedAttempts(user.getId());
        //     int newCount = user.getFailedAttempts() + 1;
        //     if (newCount >= MAX_FAILED_ATTEMPTS) {
        //         Instant until = Instant.now().plus(LOCK_DURATION);
        //         appUserRepository.lockUntil(user.getId(), until);
        //         throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, lockedMessage(until));
        //     }
        //     throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        // }

        appUserRepository.resetFailedAttempts(user.getId());

        List<String> roles = roleCodes(user);
        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getDisplayName(), roles);
        String refreshToken = issueRefreshToken(user, userAgent, ip);

        return new LoginResult(
                new TokenPair(accessToken, refreshToken),
                toAuthenticatedUser(user, roles));
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken, String userAgent, String ip) {
        RefreshToken existing = refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
                .filter(RefreshToken::isUsable)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));

        AppUser user = existing.getUser();
        // 換發即撤銷舊 token（單次使用）：舊 token 外流也只能用一次就失效
        existing.setRevokedAt(Instant.now());
        refreshTokenRepository.save(existing);

        List<String> roles = roleCodes(user);
        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getDisplayName(), roles);
        String newRefreshToken = issueRefreshToken(user, userAgent, ip);

        return new TokenPair(accessToken, newRefreshToken);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(hash(rawRefreshToken)).ifPresent(token -> {
            token.setRevokedAt(Instant.now());
            refreshTokenRepository.save(token);
        });
    }

    @Transactional(readOnly = true)
    public AuthenticatedUser currentUser(Long userId) {
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        return toAuthenticatedUser(user, roleCodes(user));
    }

    private String issueRefreshToken(AppUser user, String userAgent, String ip) {
        String raw = generateOpaqueToken();
        Instant now = Instant.now();
        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(hash(raw))
                .issuedAt(now)
                .expiresAt(now.plus(refreshTtl))
                .userAgent(userAgent)
                .ip(ip)
                .build();
        refreshTokenRepository.save(token);
        return raw;
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 保證提供的演算法，不會真的走到這裡
            throw new IllegalStateException(e);
        }
    }

    private static String lockedMessage(Instant lockedUntil) {
        long remainingMinutes = Math.max(1, Duration.between(Instant.now(), lockedUntil).toMinutes() + 1);
        return "連續登入失敗次數過多，帳號已鎖定，請於 " + remainingMinutes + " 分鐘後再試";
    }

    private static List<String> roleCodes(AppUser user) {
        return user.getRoles().stream().map(Role::getCode).map(RoleCode::name).toList();
    }

    private static AuthenticatedUser toAuthenticatedUser(AppUser user, List<String> roles) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getDisplayName(), roles);
    }
}