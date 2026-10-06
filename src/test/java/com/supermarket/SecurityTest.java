package com.supermarket;

import com.supermarket.config.SessionCookieFilter;
import com.supermarket.util.PasswordUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityTest extends IntegrationTest {

    @Test
    void passwordsAreStoredWithBcrypt() throws Exception {
        registerSuperAdmin();
        String hash = jdbc.queryForObject("SELECT password_hash FROM cashiers WHERE username = 'admin'", String.class);
        assertThat(hash).startsWith("$2").doesNotContain("admin-pass");
    }

    @Test
    void oldSha256PasswordStillWorksAndIsUpgradedOnLogin() throws Exception {
        registerSuperAdmin();
        jdbc.update("UPDATE cashiers SET password_hash = ? WHERE username = 'admin'", PasswordUtil.sha256Hex("old-pass"));

        login("admin", "old-pass");

        String hash = jdbc.queryForObject("SELECT password_hash FROM cashiers WHERE username = 'admin'", String.class);
        assertThat(hash).startsWith("$2");
        login("admin", "old-pass");
    }

    @Test
    void shortPasswordsAreStillAllowed() throws Exception {
        postJson("/auth/register", null, Map.of("fullName", "Owner", "username", "owner", "password", "1234"))
                .andExpect(status().isOk());
    }

    @Test
    void sessionsAreStoredHashedInTheDatabase() throws Exception {
        String token = registerSuperAdmin();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE token_hash = ?", Integer.class,
                PasswordUtil.sha256Hex(token))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE token_hash = ?", Integer.class,
                token)).isZero();
    }

    @Test
    void idleSessionExpires() throws Exception {
        String token = registerSuperAdmin();
        getJson("/products", token).andExpect(status().isOk());

        jdbc.update("UPDATE auth_sessions SET last_seen_at = DATEADD('HOUR', -13, CURRENT_TIMESTAMP)");

        getJson("/products", token).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions", Integer.class)).isZero();
    }

    @Test
    void accountLocksAfterFiveWrongPasswordsAndAdminCanUnlock() throws Exception {
        String admin = registerSuperAdmin();
        createUserAndLogin(admin, "arka", "CASHIER");

        for (int i = 0; i < 4; i++) {
            postJson("/auth/login", null, Map.of("username", "arka", "password", "wrong"))
                    .andExpect(status().isBadRequest());
        }
        postJson("/auth/login", null, Map.of("username", "arka", "password", "wrong"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Shumë përpjekje të gabuara. Llogaria është bllokuar për 5 minuta"));

        // Even the right password is refused while locked.
        postJson("/auth/login", null, Map.of("username", "arka", "password", "secret-pass"))
                .andExpect(status().isTooManyRequests());

        Long id = jdbc.queryForObject("SELECT id FROM cashiers WHERE username = 'arka'", Long.class);
        putJson("/users/" + id, admin, Map.of("fullName", "arka Test", "username", "arka", "role", "CASHIER", "active", true))
                .andExpect(status().isOk());
        login("arka", "secret-pass");
    }

    @Test
    void successfulLoginResetsTheWrongPasswordCount() throws Exception {
        String admin = registerSuperAdmin();
        createUserAndLogin(admin, "arka", "CASHIER");
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 4; i++) {
                postJson("/auth/login", null, Map.of("username", "arka", "password", "wrong"));
            }
            login("arka", "secret-pass");
        }
    }

    @Test
    void browserSignsInWithHttpOnlyCookie() throws Exception {
        registerSuperAdmin();
        MvcResult result = postJson("/auth/login", null, Map.of("username", "admin", "password", "admin-pass"))
                .andExpect(status().isOk()).andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains(SessionCookieFilter.COOKIE_NAME + "=").contains("HttpOnly").contains("SameSite=Strict");
        Cookie cookie = result.getResponse().getCookie(SessionCookieFilter.COOKIE_NAME);

        mvc.perform(get("/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.token").doesNotExist());
        mvc.perform(get("/products").cookie(cookie)).andExpect(status().isOk());

        MvcResult logout = mvc.perform(post("/auth/logout").cookie(cookie)).andExpect(status().isOk()).andReturn();
        assertThat(logout.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");
        mvc.perform(get("/products").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void changingAUsersPasswordSignsThemOut() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        Long id = jdbc.queryForObject("SELECT id FROM cashiers WHERE username = 'arka'", Long.class);

        putJson("/users/" + id, admin, Map.of("fullName", "arka Test", "username", "arka", "role", "CASHIER",
                "active", true, "password", "new-pass")).andExpect(status().isOk());

        getJson("/sales/cart", cashier).andExpect(status().isUnauthorized());
        getJson("/users", admin).andExpect(status().isOk());
    }

    @Test
    void newAccountsDefaultToCashierRole() {
        jdbc.update("INSERT INTO cashiers (full_name, username, password_hash) VALUES ('X', 'x', 'h')");
        assertThat(jdbc.queryForObject("SELECT role FROM cashiers WHERE username = 'x'", String.class)).isEqualTo("CASHIER");
    }

    @Test
    void unknownCrossSiteOriginGetsNoCorsPermission() throws Exception {
        MvcResult result = mvc.perform(get("/auth/setup").header("Origin", "null")).andReturn();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }
}
