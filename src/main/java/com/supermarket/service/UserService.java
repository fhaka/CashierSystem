package com.supermarket.service;

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
            throw new IllegalArgumentException("Username is already registered");
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
                .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + id));
        cashierRepository.findByUsernameIgnoreCase(request.getUsername().trim())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Username is already registered");
                });

        CashierRole newRole = parseRole(request.getRole());
        boolean newActive = request.getActive() == null || request.getActive();
        if (cashier.getId().equals(currentSuperAdmin.getId()) && (!newRole.isSuperAdmin() || !newActive)) {
            throw new IllegalArgumentException("You cannot remove your own Super Admin access");
        }
        if (cashier.getRole().isSuperAdmin() && cashier.isActive()
                && (!newRole.isSuperAdmin() || !newActive)
                && cashierRepository.countByRoleAndActiveTrue(CashierRole.SUPER_ADMIN) <= 1) {
            throw new IllegalArgumentException("At least one active Super Admin is required");
        }

        cashier.setFullName(request.getFullName().trim());
        cashier.setUsername(request.getUsername().trim());
        cashier.setRole(newRole);
        cashier.setActive(newActive);
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            if (request.getPassword().length() < 4) {
                throw new IllegalArgumentException("Password must be at least 4 characters");
            }
            cashier.setPasswordHash(PasswordUtil.hash(request.getPassword()));
        }
        Cashier saved = cashierRepository.save(cashier);
        if (!saved.isActive()) {
            sessionService.invalidateUser(saved.getId());
        }
        return toResponse(saved);
    }

    private void validate(UserRequest request, boolean passwordRequired) {
        if (request.getFullName() == null || request.getFullName().isBlank()) {
            throw new IllegalArgumentException("Full name is required");
        }
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (passwordRequired && (request.getPassword() == null || request.getPassword().length() < 4)) {
            throw new IllegalArgumentException("Password must be at least 4 characters");
        }
        parseRole(request.getRole());
    }

    private CashierRole parseRole(String role) {
        try {
            return CashierRole.valueOf(role == null ? "CASHIER" : role.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Role must be SUPER_ADMIN, SUPER_CASHIER, or CASHIER");
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
