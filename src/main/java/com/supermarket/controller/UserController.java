package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.dto.UserRequest;
import com.supermarket.dto.UserResponse;
import com.supermarket.model.Cashier;
import com.supermarket.service.SessionService;
import com.supermarket.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;
    private final SessionService sessionService;

    public UserController(UserService userService, SessionService sessionService) {
        this.userService = userService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<UserResponse>> findAll(
            @RequestHeader(value = "X-Auth-Token", required = false) String token
    ) {
        sessionService.requireSuperAdmin(token);
        return ApiResponse.ok("Users loaded", userService.findAll());
    }

    @PostMapping
    public ApiResponse<UserResponse> create(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestBody UserRequest request
    ) {
        sessionService.requireSuperAdmin(token);
        return ApiResponse.ok("User created", userService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<UserResponse> update(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody UserRequest request
    ) {
        Cashier superAdmin = sessionService.requireSuperAdmin(token);
        return ApiResponse.ok("User updated", userService.update(id, request, superAdmin));
    }
}
