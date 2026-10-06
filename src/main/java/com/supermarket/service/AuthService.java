package com.supermarket.service;

import com.supermarket.exception.AccountLockedException;
import com.supermarket.exception.ValidationException;
import com.supermarket.dto.AuthRequest;
import com.supermarket.dto.AuthResponse;
import com.supermarket.dto.RegisterRequest;
import com.supermarket.model.Cashier;
import com.supermarket.model.CashierRole;
import com.supermarket.repository.CashierRepository;
import com.supermarket.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AuthService {

    private static final String UNKNOWN_USER_HASH = PasswordUtil.hash("unknown-user-placeholder");

    private final CashierRepository cashierRepository;
    private final SessionService sessionService;
    private final int maxFailedLogins;
    private final Duration lockDuration;

    public AuthService(
            CashierRepository cashierRepository,
            SessionService sessionService,
            @Value("${pos.login.max-attempts:5}") int maxFailedLogins,
            @Value("${pos.login.lock-duration:5m}") Duration lockDuration
    ) {
        this.cashierRepository = cashierRepository;
        this.sessionService = sessionService;
        this.maxFailedLogins = maxFailedLogins;
        this.lockDuration = lockDuration;
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

    // A wrong password is counted and then rejected: keep the count despite the exception.
    @Transactional(noRollbackFor = {ValidationException.class, AccountLockedException.class})
    public AuthResponse login(AuthRequest request) {
        if (request.getUsername() == null || request.getPassword() == null) {
            throw new ValidationException("auth.credentialsRequired");
        }

        Optional<Cashier> found = cashierRepository.findByUsernameIgnoreCase(request.getUsername().trim());
        if (found.isEmpty()) {
            // Same BCrypt work as a real check, so response time does not reveal which usernames exist.
            PasswordUtil.matches(request.getPassword(), UNKNOWN_USER_HASH);
            throw new ValidationException("auth.invalidCredentials");
        }
        Cashier cashier = found.get();

        LocalDateTime now = LocalDateTime.now();
        if (cashier.getLockedUntil() != null && cashier.getLockedUntil().isAfter(now)) {
            throw new AccountLockedException(minutesUntil(now, cashier.getLockedUntil()));
        }

        if (!PasswordUtil.matches(request.getPassword(), cashier.getPasswordHash())) {
            registerFailedLogin(cashier, now);
            throw new ValidationException("auth.invalidCredentials");
        }

        if (!cashier.isActive()) {
            throw new ValidationException("auth.accountDisabled");
        }

        cashier.setFailedLogins(0);
        cashier.setLockedUntil(null);
        if (PasswordUtil.needsUpgrade(cashier.getPasswordHash())) {
            cashier.setPasswordHash(PasswordUtil.hash(request.getPassword()));
        }
        return toResponse(cashier, sessionService.createSession(cashier));
    }

    public AuthResponse currentUser(String token) {
        return toResponse(sessionService.requireUser(token), null);
    }

    private void registerFailedLogin(Cashier cashier, LocalDateTime now) {
        int failures = cashier.getFailedLogins() + 1;
        if (failures >= maxFailedLogins) {
            cashier.setFailedLogins(0);
            cashier.setLockedUntil(now.plus(lockDuration));
            throw new AccountLockedException(minutesUntil(now, cashier.getLockedUntil()));
        }
        cashier.setFailedLogins(failures);
    }

    private static long minutesUntil(LocalDateTime now, LocalDateTime until) {
        return Math.max(1, (Duration.between(now, until).getSeconds() + 59) / 60);
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
