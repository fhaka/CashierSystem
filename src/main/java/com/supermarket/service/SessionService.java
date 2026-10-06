package com.supermarket.service;

import com.supermarket.exception.AuthenticationRequiredException;
import com.supermarket.exception.PermissionDeniedException;
import com.supermarket.model.Cashier;
import com.supermarket.model.CashierRole;
import com.supermarket.repository.CashierRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class SessionService {

    private final CashierRepository cashierRepository;
    private final ConcurrentMap<String, Long> sessions = new ConcurrentHashMap<>();

    public SessionService(CashierRepository cashierRepository) {
        this.cashierRepository = cashierRepository;
    }

    public String createSession(Cashier cashier) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, cashier.getId());
        return token;
    }

    public Cashier requireUser(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthenticationRequiredException("Please sign in to continue");
        }
        Long cashierId = sessions.get(token);
        if (cashierId == null) {
            throw new AuthenticationRequiredException("Session expired. Please sign in again");
        }
        Cashier cashier = cashierRepository.findById(cashierId)
                .orElseThrow(() -> new AuthenticationRequiredException("Account no longer exists"));
        if (!cashier.isActive()) {
            sessions.remove(token);
            throw new AuthenticationRequiredException("This account is disabled");
        }
        return cashier;
    }

    public Cashier requireSuperAdmin(String token) {
        Cashier cashier = requireUser(token);
        if (!cashier.getRole().isSuperAdmin()) {
            throw new PermissionDeniedException("Super Admin access is required");
        }
        return cashier;
    }

    public Cashier requireOperationalManager(String token) {
        Cashier cashier = requireUser(token);
        if (!cashier.getRole().isOperationalManager()) {
            throw new PermissionDeniedException("Super Cashier or Super Admin access is required");
        }
        return cashier;
    }

    public void invalidate(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public void invalidateUser(Long cashierId) {
        sessions.entrySet().removeIf(entry -> entry.getValue().equals(cashierId));
    }
}
