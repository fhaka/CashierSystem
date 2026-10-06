package com.supermarket.service;

import com.supermarket.exception.AuthenticationRequiredException;
import com.supermarket.exception.PermissionDeniedException;
import com.supermarket.model.AuthSession;
import com.supermarket.model.Cashier;
import com.supermarket.repository.AuthSessionRepository;
import com.supermarket.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Sign-in sessions, stored in the database so a server restart does not sign the tills out.
 * A session ends after {@code pos.session.idle-timeout} without any request.
 */
@Service
public class SessionService {

    /** Avoid a database write on every request: last-seen time is refreshed at most once a minute. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final AuthSessionRepository sessionRepository;
    private final Duration idleTimeout;
    private final SecureRandom random = new SecureRandom();

    public SessionService(
            AuthSessionRepository sessionRepository,
            @Value("${pos.session.idle-timeout:12h}") Duration idleTimeout
    ) {
        this.sessionRepository = sessionRepository;
        this.idleTimeout = idleTimeout;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    @Transactional
    public String createSession(Cashier cashier) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessionRepository.save(new AuthSession(PasswordUtil.sha256Hex(token), cashier, LocalDateTime.now()));
        return token;
    }

    // Expired or disabled sessions are deleted and then rejected: keep the delete despite the exception.
    @Transactional(noRollbackFor = AuthenticationRequiredException.class)
    public Cashier requireUser(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthenticationRequiredException("auth.signInRequired");
        }
        AuthSession session = sessionRepository.findById(PasswordUtil.sha256Hex(token))
                .orElseThrow(() -> new AuthenticationRequiredException("auth.sessionExpired"));

        LocalDateTime now = LocalDateTime.now();
        if (session.getLastSeenAt().plus(idleTimeout).isBefore(now)) {
            sessionRepository.delete(session);
            throw new AuthenticationRequiredException("auth.sessionExpired");
        }
        Cashier cashier = session.getCashier();
        if (!cashier.isActive()) {
            sessionRepository.delete(session);
            throw new AuthenticationRequiredException("auth.accountDisabled");
        }
        if (session.getLastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {
            session.setLastSeenAt(now);
        }
        return cashier;
    }

    public Cashier requireSuperAdmin(String token) {
        Cashier cashier = requireUser(token);
        if (!cashier.getRole().isSuperAdmin()) {
            throw new PermissionDeniedException("auth.superAdminRequired");
        }
        return cashier;
    }

    public Cashier requireOperationalManager(String token) {
        Cashier cashier = requireUser(token);
        if (!cashier.getRole().isOperationalManager()) {
            throw new PermissionDeniedException("auth.managerRequired");
        }
        return cashier;
    }

    @Transactional
    public void invalidate(String token) {
        if (token != null && !token.isBlank()) {
            sessionRepository.deleteById(PasswordUtil.sha256Hex(token));
        }
    }

    @Transactional
    public void invalidateUser(Long cashierId) {
        sessionRepository.deleteByCashierId(cashierId);
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void removeIdleSessions() {
        sessionRepository.deleteIdleSince(LocalDateTime.now().minus(idleTimeout));
    }
}
