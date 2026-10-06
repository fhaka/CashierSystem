package com.supermarket.service;

import com.supermarket.exception.ValidationException;
import com.supermarket.dto.UserRequest;
import com.supermarket.dto.UserResponse;
import com.supermarket.model.Cashier;
import com.supermarket.model.CashierRole;
import com.supermarket.repository.CashierRepository;
import com.supermarket.util.PasswordUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final CashierRepository cashierRepository;
    private final SessionService sessionService;

    public UserService(CashierRepository cashierRepository, SessionService sessionService) {
        this.cashierRepository = cashierRepository;
        this.sessionService = sessionService;
    }

    public List<UserResponse> findAll() {
        return cashierRepository.findAllByOrderByFullNameAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public UserResponse create(UserRequest request) {
        validate(request, true);
        String username = request.getUsername().trim();
        if (cashierRepository.existsByUsernameIgnoreCase(username)) {
            throw new ValidationException("user.usernameTaken");
        }
        Cashier cashier = new Cashier(
                request.getFullName().trim(),
                username,
                PasswordUtil.hash(request.getPassword()),
                parseRole(request.getRole())
        );
        cashier.setActive(request.getActive() == null || request.getActive());
        return toResponse(cashierRepository.save(cashier));
    }

    @Transactional
    public UserResponse update(Long id, UserRequest request, Cashier currentSuperAdmin) {
        validate(request, false);
        Cashier cashier = cashierRepository.findById(id)
                .orElseThrow(() -> new ValidationException("user.notFound", id));
        cashierRepository.findByUsernameIgnoreCase(request.getUsername().trim())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new ValidationException("user.usernameTaken");
                });

        CashierRole newRole = parseRole(request.getRole());
        boolean newActive = request.getActive() == null || request.getActive();
        if (cashier.getId().equals(currentSuperAdmin.getId()) && (!newRole.isSuperAdmin() || !newActive)) {
            throw new ValidationException("user.cannotRemoveOwnAdmin");
        }
        if (cashier.getRole().isSuperAdmin() && cashier.isActive()
                && (!newRole.isSuperAdmin() || !newActive)
                && cashierRepository.countByRoleAndActiveTrue(CashierRole.SUPER_ADMIN) <= 1) {
            throw new ValidationException("user.lastSuperAdmin");
        }

        CashierRole previousRole = cashier.getRole();
        cashier.setFullName(request.getFullName().trim());
        cashier.setUsername(request.getUsername().trim());
        cashier.setRole(newRole);
        cashier.setActive(newActive);
        // Saving a user in the admin screen also unlocks an account locked by wrong passwords.
        cashier.setFailedLogins(0);
        cashier.setLockedUntil(null);
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            if (request.getPassword().length() < 4) {
                throw new ValidationException("user.passwordTooShort");
            }
            cashier.setPasswordHash(PasswordUtil.hash(request.getPassword()));
        }
        boolean passwordChanged = request.getPassword() != null && !request.getPassword().isBlank();
        boolean accessChanged = previousRole != newRole || !newActive;
        Cashier saved = cashierRepository.save(cashier);
        // Sign the user out everywhere when their access or password changes (the admin keeps their own session).
        if ((accessChanged || passwordChanged) && !saved.getId().equals(currentSuperAdmin.getId())) {
            sessionService.invalidateUser(saved.getId());
        }
        return toResponse(saved);
    }

    private void validate(UserRequest request, boolean passwordRequired) {
        if (request.getFullName() == null || request.getFullName().isBlank()) {
            throw new ValidationException("user.fullNameRequired");
        }
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new ValidationException("user.usernameRequired");
        }
        if (passwordRequired && (request.getPassword() == null || request.getPassword().length() < 4)) {
            throw new ValidationException("user.passwordTooShort");
        }
        parseRole(request.getRole());
    }

    private CashierRole parseRole(String role) {
        try {
            return CashierRole.valueOf(role == null ? "CASHIER" : role.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("user.invalidRole");
        }
    }

    private UserResponse toResponse(Cashier cashier) {
        return new UserResponse(
                cashier.getId(),
                cashier.getFullName(),
                cashier.getUsername(),
                cashier.getRole().name(),
                cashier.isActive()
        );
    }
}
