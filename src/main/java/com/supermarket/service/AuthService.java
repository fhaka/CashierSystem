package com.supermarket.service;

import com.supermarket.dto.AuthRequest;
import com.supermarket.dto.AuthResponse;
import com.supermarket.dto.RegisterRequest;
import com.supermarket.model.Cashier;
import com.supermarket.repository.CashierRepository;
import com.supermarket.util.PasswordUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final CashierRepository cashierRepository;

    public AuthService(CashierRepository cashierRepository) {
        this.cashierRepository = cashierRepository;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        validateRegisterRequest(request);
        String username = request.getUsername().trim();
        if (cashierRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("Username is already registered");
        }

        Cashier cashier = new Cashier(
                request.getFullName().trim(),
                username,
                PasswordUtil.hash(request.getPassword())
        );
        Cashier savedCashier = cashierRepository.save(cashier);
        return toResponse(savedCashier);
    }

    public AuthResponse login(AuthRequest request) {
        if (request.getUsername() == null || request.getPassword() == null) {
            throw new IllegalArgumentException("Username and password are required");
        }

        Cashier cashier = cashierRepository.findByUsernameIgnoreCase(request.getUsername().trim())
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));

        if (!cashier.getPasswordHash().equals(PasswordUtil.hash(request.getPassword()))) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        return toResponse(cashier);
    }

    private void validateRegisterRequest(RegisterRequest request) {
        if (request.getFullName() == null || request.getFullName().isBlank()) {
            throw new IllegalArgumentException("Full name is required");
        }
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (request.getPassword() == null || request.getPassword().length() < 4) {
            throw new IllegalArgumentException("Password must be at least 4 characters");
        }
    }

    private AuthResponse toResponse(Cashier cashier) {
        return new AuthResponse(cashier.getId(), cashier.getFullName(), cashier.getUsername());
    }
}
