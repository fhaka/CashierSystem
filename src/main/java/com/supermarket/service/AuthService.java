package com.supermarket.service;

import com.supermarket.exception.ValidationException;
import com.supermarket.dto.AuthRequest;
import com.supermarket.dto.AuthResponse;
import com.supermarket.dto.RegisterRequest;
import com.supermarket.model.Cashier;
import com.supermarket.model.CashierRole;
import com.supermarket.repository.CashierRepository;
import com.supermarket.util.PasswordUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final CashierRepository cashierRepository;
    private final SessionService sessionService;

    public AuthService(CashierRepository cashierRepository, SessionService sessionService) {
        this.cashierRepository = cashierRepository;
        this.sessionService = sessionService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        validateRegisterRequest(request);
        if (cashierRepository.count() > 0) {
            throw new ValidationException("auth.registrationClosed");
        }
        String username = request.getUsername().trim();
        if (cashierRepository.existsByUsernameIgnoreCase(username)) {
            throw new ValidationException("user.usernameTaken");
        }

        Cashier cashier = new Cashier(
                request.getFullName().trim(),
                username,
                PasswordUtil.hash(request.getPassword()),
                CashierRole.SUPER_ADMIN
        );
        Cashier savedCashier = cashierRepository.save(cashier);
        return toResponse(savedCashier, sessionService.createSession(savedCashier));
    }

    @Transactional
    public AuthResponse login(AuthRequest request) {
        if (request.getUsername() == null || request.getPassword() == null) {
            throw new ValidationException("auth.credentialsRequired");
        }

        Cashier cashier = cashierRepository.findByUsernameIgnoreCase(request.getUsername().trim())
                .orElseThrow(() -> new ValidationException("auth.invalidCredentials"));

        if (!cashier.getPasswordHash().equals(PasswordUtil.hash(request.getPassword()))) {
            throw new ValidationException("auth.invalidCredentials");
        }

        if (!cashier.isActive()) {
            throw new ValidationException("auth.accountDisabled");
        }

        return toResponse(cashier, sessionService.createSession(cashier));
    }

    public boolean isInitialRegistrationAvailable() {
        return cashierRepository.count() == 0;
    }

    public void logout(String token) {
        sessionService.invalidate(token);
    }

    private void validateRegisterRequest(RegisterRequest request) {
        if (request.getFullName() == null || request.getFullName().isBlank()) {
            throw new ValidationException("user.fullNameRequired");
        }
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new ValidationException("user.usernameRequired");
        }
        if (request.getPassword() == null || request.getPassword().length() < 4) {
            throw new ValidationException("user.passwordTooShort");
        }
    }

    private AuthResponse toResponse(Cashier cashier, String token) {
        return new AuthResponse(
                cashier.getId(),
                cashier.getFullName(),
                cashier.getUsername(),
                cashier.getRole().name(),
                token
        );
    }
}
