package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.AuthRequest;
import com.supermarket.dto.AuthResponse;
import com.supermarket.dto.RegisterRequest;
import com.supermarket.service.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@RequestBody RegisterRequest request) {
        return ApiResponse.ok("Cashier registered", authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@RequestBody AuthRequest request) {
        return ApiResponse.ok("Cashier logged in", authService.login(request));
    }

    @org.springframework.web.bind.annotation.GetMapping("/setup")
    public ApiResponse<Map<String, Boolean>> setupStatus() {
        return ApiResponse.ok(
                "Initial registration status loaded",
                Map.of("registrationAvailable", authService.isInitialRegistrationAvailable())
        );
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = "X-Auth-Token", required = false) String token
    ) {
        authService.logout(token);
        return ApiResponse.ok("Signed out", null);
    }
}
