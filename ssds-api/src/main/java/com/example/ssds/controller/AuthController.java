package com.example.ssds.controller;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.ssds.service.AuthService;

@RestController
@RequestMapping("/api/v1/auth") 
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class AuthController {

    @Autowired 
    private AuthService authService;

    @PostMapping("/login") // 組合出來為 POST /api/v1/auth/login
    public ResponseEntity<Object> login(@RequestBody Map<String, String> body) {
        return authService.authenticate(body);
    }
}