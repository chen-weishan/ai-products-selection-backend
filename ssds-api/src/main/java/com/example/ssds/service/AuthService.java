package com.example.ssds.service;

import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.time.Instant;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssds.api.common.response.ApiError;
import com.example.ssds.core.domain.UserStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.util.JwtUtils;

@Service
public class AuthService {

    @Autowired
    private AppUserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;
    @Transactional
    public ResponseEntity<Object> authenticate(Map<String, String> body) {
        
        String email = body.get("email");
        String password = body.get("password");

        Optional<AppUser> userOptional = userRepository.findByEmail(email);

        // 1. 防禦邏輯：找不到帳號回傳 401（規格書要求不透露是帳號不存在）
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(401).body(new ApiError("AUTH_FAILED", "帳號或密碼錯誤。"));
        }

        AppUser user = userOptional.get();
       
        // 2. 處理時間軸自動解鎖：若當前時間已超過解鎖時間，當場重設歸零
        if (user.getLockedUntil() != null && Instant.now().isAfter(user.getLockedUntil())) {
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
            userRepository.saveAndFlush(user);
        }

        // 3. 檢查基本狀態（停用）
        if (user.getStatus() == UserStatus.DISABLED) {
            return ResponseEntity.status(403).body(new ApiError("AUTH_DISABLED", "此帳號已停用，請聯絡系統管理員"));
        }

        // 4. 檢查是否還在鎖定時間內：若在時間內，直接阻擋並中斷
        if (user.getLockedUntil() != null && Instant.now().isBefore(user.getLockedUntil())) {
            return ResponseEntity.status(403).body(new ApiError("AUTH_LOCKED", "嘗試次數過多，帳號已被鎖定，請稍後再試。"));
        }
	
        // 5. 密碼比對與計數邏輯
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            
            int newAttempts = user.getFailedAttempts() + 1;
            user.setFailedAttempts(newAttempts);

            if (newAttempts >= 5) {
                user.setFailedAttempts(0); // 依規格書 v2.0，鎖定時次數重置為 0
                user.setLockedUntil(Instant.now().plusSeconds(15 * 60)); // 鎖定 15 分鐘
                userRepository.saveAndFlush(user); 
                
                return ResponseEntity.status(403).body(new ApiError("AUTH_LOCKED", "嘗試次數過多，帳號已被鎖定 15 分鐘。"));
            } else {
                userRepository.saveAndFlush(user); // 實打實將次數 +1 寫入資料庫
                System.out.println("====== [測試驗收] 帳號 " + email + " 密碼輸入錯誤！當前累計失敗次數為: " + newAttempts + " ======");
                return ResponseEntity.status(401).body(new ApiError("AUTH_FAILED", "帳號或密碼錯誤。"));
            }
        }

        // 6. 登入成功：清空所有失敗狀態
        user.setFailedAttempts(0); 
        user.setLockedUntil(null);
        userRepository.saveAndFlush(user);

        String realAccessToken = jwtUtils.generateToken(user);

        List<String> rolesStrList = user.getRoles().stream()
                .map(role -> role.getCode().name())
                .collect(Collectors.toList());

        Map<String, Object> successPack = new HashMap<>();
        successPack.put("accessToken", realAccessToken);
        successPack.put("email", user.getEmail());
        successPack.put("displayName", user.getDisplayName());
        successPack.put("roles", rolesStrList); 

        return ResponseEntity.ok(successPack); 
    }
}