package com.supermarket;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowTest extends IntegrationTest {

    @Test
    void firstRegistrationCreatesSuperAdminAndClosesRegistration() throws Exception {
        getJson("/auth/setup", null).andExpect(jsonPath("$.data.registrationAvailable").value(true));

        postJson("/auth/register", null, Map.of("fullName", "Owner", "username", "owner", "password", "owner-pass"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"));

        getJson("/auth/setup", null).andExpect(jsonPath("$.data.registrationAvailable").value(false));
        postJson("/auth/register", null, Map.of("fullName", "Intruder", "username", "intruder", "password", "intruder"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        registerSuperAdmin();
        postJson("/auth/login", null, Map.of("username", "admin", "password", "wrong-pass"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestsWithoutValidTokenAreUnauthorized() throws Exception {
        getJson("/products", null).andExpect(status().isUnauthorized());
        getJson("/products", "not-a-real-token").andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        String token = registerSuperAdmin();
        postJson("/auth/logout", token, null).andExpect(status().isOk());
        getJson("/products", token).andExpect(status().isUnauthorized());
    }

    @Test
    void cashierCannotReachManagerOrAdminEndpoints() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka1", "CASHIER");

        postJson("/products", cashier, Map.of("name", "X", "barcode", "1", "price", "1", "purchasePrice", "1",
                "taxRate", "20", "stock", 1)).andExpect(status().isForbidden());
        getJson("/users", cashier).andExpect(status().isForbidden());
        getJson("/reports/sales", cashier).andExpect(status().isForbidden());
    }

    @Test
    void disabledUserIsLoggedOutImmediately() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka2", "CASHIER");
        Long cashierId = jdbc.queryForObject("SELECT id FROM cashiers WHERE username = 'arka2'", Long.class);

        putJson("/users/" + cashierId, admin, Map.of("fullName", "arka2 Test", "username", "arka2",
                "role", "CASHIER", "active", false)).andExpect(status().isOk());

        getJson("/sales/cart", cashier).andExpect(status().isUnauthorized());
    }
}
