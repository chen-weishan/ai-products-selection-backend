package com.example.ssds.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.ssds.api.common.response.ApiError;
import com.example.ssds.core.domain.UserStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.util.JwtUtils;

@Service
@org.springframework.transaction.annotation.Transactional // 🌟 晶片插在這裡！強迫整個方法結束時，必定實打實把資料庫改動提交出去！
public class AuthService {

	@Autowired
	private AppUserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtUtils jwtUtils;
	
	public ResponseEntity<Object> authenticate(java.util.Map<String, String> body) {
	        
	        String email = body.get("email");
	        String password = body.get("password");

	        Optional<AppUser> userOptional = userRepository.findByEmail(email);

	        if (userOptional.isEmpty()) {
	            return ResponseEntity.status(401).body(new ApiError("AUTH_FAILED", "帳號或密碼錯誤。"));
	        }

	        AppUser user = userOptional.get();
	       
	        if (user.getLockedUntil() != null && java.time.Instant.now().isAfter(user.getLockedUntil())) {
	            user.setFailedAttempts(0);
	            user.setLockedUntil(null);
	            userRepository.saveAndFlush(user);
	        }

	        if (!user.isLoginAllowed()) {
	            if (user.getStatus() == UserStatus.DISABLED) {
	                return ResponseEntity.status(403).body(new ApiError("AUTH_DISABLED", "此帳號已停用，請聯絡系統管理員"));
	            } else {
	                return ResponseEntity.status(403).body(new ApiError("AUTH_LOCKED", "嘗試次數過多，帳號已被鎖定，請稍後再試。"));
	            }
	        }
	        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
	            
	        	user.setFailedAttempts(user.getFailedAttempts() + 1);

	            if (user.getFailedAttempts() >= 5) {
	                user.setFailedAttempts(0); 
	                user.setLockedUntil(java.time.Instant.now().plusSeconds(15 * 60)); 
	                userRepository.saveAndFlush(user); 
	                
	                return ResponseEntity.status(403).body(new ApiError("AUTH_LOCKED", "嘗試次數過多，帳號已被鎖定 15 分鐘。"));
	            } 
	    
	            else {
	                userRepository.saveAndFlush(user); 
	                
	                return ResponseEntity.status(401).body(new ApiError("AUTH_FAILED", "帳號或密碼錯誤。"));
	            }
	        }

	        user.setFailedAttempts(0); 
	        user.setLockedUntil(null);
	        userRepository.saveAndFlush(user);

	        String realAccessToken = jwtUtils.generateToken(user);

	        java.util.Map<String, Object> successPack = new java.util.HashMap<>();
	        successPack.put("accessToken", realAccessToken);
	        successPack.put("email", user.getEmail());
	        successPack.put("displayName", user.getDisplayName());
	        successPack.put("roles", user.getRoles());

	        return ResponseEntity.ok(successPack); 
	    }
}