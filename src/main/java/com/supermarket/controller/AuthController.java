package com.supermarket.controller;

import com.supermarket.config.SessionCookieFilter;
import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.AuthRequest;
import com.supermarket.dto.AuthResponse;
import com.supermarket.dto.RegisterRequest;
import com.supermarket.service.AuthService;
import com.supermarket.service.SessionService;
import com.supermarket.service.ShopSettingsService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionService sessionService;
    private final ShopSettingsService shopSettingsService;

    public AuthController(AuthService authService, SessionService sessionService, ShopSettingsService shopSettingsService) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.shopSettingsService = shopSettingsService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@RequestBody RegisterRequest request, HttpServletResponse response) {
        return ApiResponse.ok("Cashier registered", signIn(authService.register(request), response));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@RequestBody AuthRequest request, HttpServletResponse response) {
        return ApiResponse.ok("Cashier logged in", signIn(authService.login(request), response));
    }

    /** Who is signed in on this till. Lets the screen restore the session after a page reload. */
    @GetMapping("/me")
    public ApiResponse<AuthResponse> me(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        return ApiResponse.ok("Signed in", authService.currentUser(token));
    }

    @GetMapping("/setup")
    public ApiResponse<Map<String, Object>> setupStatus() {
        return ApiResponse.ok(
                "Initial registration status loaded",
                Map.of("registrationAvailable", authService.isInitialRegistrationAvailable(),
                        "shopName", shopSettingsService.get().name())
        );
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            HttpServletResponse response
    ) {
        authService.logout(token);
        SessionCookieFilter.clearCookie(response);
        return ApiResponse.ok("Signed out", null);
    }

    private AuthResponse signIn(AuthResponse auth, HttpServletResponse response) {
        SessionCookieFilter.writeCookie(response, auth.getToken(), sessionService.getIdleTimeout());
        return auth;
    }
}
