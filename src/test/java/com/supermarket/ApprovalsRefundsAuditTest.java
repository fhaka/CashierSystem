package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalsRefundsAuditTest extends IntegrationTest {

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM cashiers WHERE username = ?", Long.class, username);
    }

    private void setPin(String adminToken, String username, String role, String pin) throws Exception {
        putJson("/users/" + userId(username), adminToken, Map.of("fullName", username + " Test", "username", username,
                "role", role, "active", true, "approvalPin", pin)).andExpect(status().isOk());
    }

    private ResultActions withPin(String method, String path, String token, String pin, Object body) throws Exception {
        var request = switch (method) {
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> post(path);
        };
        if (pin != null) {
            request.header("X-Approval-Pin", pin);
        }
        return call(request, token, body);
    }

    private void addToCart(String token, long productId, int quantity) throws Exception {
        postJson("/sales/cart", token, Map.of("productId", productId, "quantity", quantity)).andExpect(status().isOk());
    }

    @Test
    void cashierChangesPriceOnlyWithAManagerPin() throws Exception {
        String admin = registerSuperAdmin();
        setPin(admin, "admin", "SUPER_ADMIN", "4321");
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long product = createProduct(admin, "600000000001", "100.00", 10);
        addToCart(cashier, product, 1);

        withPin("PUT", "/sales/cart/" + product, cashier, null, Map.of("price", "80.00"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("approval.required"));
        withPin("PUT", "/sales/cart/" + product, cashier, "1111", Map.of("price", "80.00"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("approval.invalidPin"));
        withPin("PUT", "/sales/cart/" + product, cashier, "4321", Map.of("price", "80.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].price").value(80.00));

        Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM audit_events WHERE action = 'PRICE_OVERRIDE'");
        assertThat(audit.get("approved_by_id")).isEqualTo(userId("admin"));
        assertThat(audit.get("cashier_id")).isEqualTo(userId("arka"));
        assertThat((String) audit.get("details")).contains("100.00 -> 80.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'APPROVAL_FAILED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void cashierVoidsNeedApprovalButAddingDoesNot() throws Exception {
        String admin = registerSuperAdmin();
        createUserAndLogin(admin, "shef", "SUPER_CASHIER");
        setPin(admin, "shef", "SUPER_CASHIER", "2468");
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long product = createProduct(admin, "600000000002", "10.00", 10);
        addToCart(cashier, product, 3);

        withPin("PUT", "/sales/cart/" + product, cashier, null, Map.of("quantity", 5)).andExpect(status().isOk());
        withPin("PUT", "/sales/cart/" + product, cashier, null, Map.of("quantity", 2)).andExpect(status().isForbidden());
        withPin("PUT", "/sales/cart/" + product, cashier, "2468", Map.of("quantity", 2)).andExpect(status().isOk());
        withPin("DELETE", "/sales/cart", cashier, null, null).andExpect(status().isForbidden());
        withPin("DELETE", "/sales/cart", cashier, "2468", null).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT action FROM audit_events WHERE action LIKE 'CART%' ORDER BY id", String.class))
                .containsExactly("CART_LINE_VOID", "CART_CLEARED");

        // Managers approve their own voids.
        addToCart(admin, product, 2);
        withPin("DELETE", "/sales/cart", admin, null, null).andExpect(status().isOk());
    }

    @Test
    void tooManyWrongPinsPauseApprovals() throws Exception {
        String admin = registerSuperAdmin();
        setPin(admin, "admin", "SUPER_ADMIN", "4321");
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long product = createProduct(admin, "600000000003", "10.00", 10);
        addToCart(cashier, product, 1);

        for (int i = 0; i < 5; i++) {
            withPin("PUT", "/sales/cart/" + product, cashier, "000" + i, Map.of("price", "5.00"));
        }
        withPin("PUT", "/sales/cart/" + product, cashier, "4321", Map.of("price", "5.00"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("approval.paused"));
    }

    @Test
    void pinRules() throws Exception {
        String admin = registerSuperAdmin();
        createUserAndLogin(admin, "arka", "CASHIER");
        createUserAndLogin(admin, "shef", "SUPER_CASHIER");
        setPin(admin, "admin", "SUPER_ADMIN", "4321");

        putJson("/users/" + userId("arka"), admin, Map.of("fullName", "arka Test", "username", "arka", "role", "CASHIER",
                "active", true, "approvalPin", "1234")).andExpect(status().isBadRequest());
        putJson("/users/" + userId("shef"), admin, Map.of("fullName", "shef Test", "username", "shef", "role", "SUPER_CASHIER",
                "active", true, "approvalPin", "4321")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("approval.pinTaken"));
        putJson("/users/" + userId("shef"), admin, Map.of("fullName", "shef Test", "username", "shef", "role", "SUPER_CASHIER",
                "active", true, "approvalPin", "12a")).andExpect(status().isBadRequest());
        getJson("/users", admin).andExpect(jsonPath("$.data[?(@.username == 'admin')].hasApprovalPin").value(true));
    }

    @Test
    void partialRefundsReturnStockAndMoneyUntilNothingIsLeft() throws Exception {
        String admin = registerSuperAdmin();
        setPin(admin, "admin", "SUPER_ADMIN", "4321");
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long product = createProduct(admin, "600000000004", "100.00", 10);
        long shiftId = openShift(cashier, "1000.00");
        addToCart(cashier, product, 3);
        postJson("/sales/checkout", cashier, null).andExpect(status().isOk());

        JsonNode sale = data(getJson("/sales/by-invoice/1", cashier).andExpect(status().isOk()));
        assertThat(sale.path("invoiceNumber").asText()).isEqualTo("000001");
        long saleId = sale.path("saleId").asLong();
        long saleItemId = sale.path("lines").get(0).path("saleItemId").asLong();
        assertThat(sale.path("lines").get(0).path("refundableQuantity").decimalValue()).isEqualByComparingTo("3");

        Map<String, Object> one = Map.of("lines", List.of(Map.of("saleItemId", saleItemId, "quantity", 1)),
                "method", "CASH", "reason", "Produkt i dëmtuar");
        withPin("POST", "/sales/" + saleId + "/refunds", cashier, null, one).andExpect(status().isForbidden());
        JsonNode first = data(withPin("POST", "/sales/" + saleId + "/refunds", cashier, "4321", one).andExpect(status().isOk()));
        assertThat(first.path("refundNumber").asText()).isEqualTo("R000001");
        assertThat(first.path("totalAmount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(first.path("approvedByName").asText()).isEqualTo("admin Test");
        assertThat(first.path("printableReceipt").asText()).startsWith("KTHIM MALLI").contains("Për faturën: 000001");
        assertThat(stockOf(product)).isEqualTo(8);

        Map<String, Object> rest = Map.of("lines", List.of(Map.of("saleItemId", saleItemId, "quantity", 2)),
                "method", "CARD", "reason", "Klienti ndryshoi mendje");
        JsonNode second = data(withPin("POST", "/sales/" + saleId + "/refunds", cashier, "4321", rest).andExpect(status().isOk()));
        assertThat(second.path("refundNumber").asText()).isEqualTo("R000002");
        assertThat(stockOf(product)).isEqualTo(10);

        withPin("POST", "/sales/" + saleId + "/refunds", cashier, "4321", one)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("refund.tooMuch"));

        // 300 cash in, 100 cash back out of the drawer, 200 back to a card: expected 1000 + 300 - 100.
        JsonNode shift = data(postJson("/shifts/" + shiftId + "/close", cashier, Map.of("closingCash", "1200.00")));
        assertThat(shift.path("expectedCash").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(shift.path("cashRefunds").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(shift.path("cardRefunds").decimalValue()).isEqualByComparingTo("200.00");
    }

    @Test
    void refundGivesBackTheDiscountedAmountAndVatFollowsInTheZReport() throws Exception {
        String admin = registerSuperAdmin();
        long tv = createProduct(admin, "600000000005", "18000.00", 5);
        long other = createProduct(admin, "600000000006", "6000.00", 5);
        openShift(admin, "0");
        addToCart(admin, tv, 1);
        addToCart(admin, other, 1);
        postJson("/sales/checkout", admin, null).andExpect(status().isOk());
        JsonNode sale = data(getJson("/sales/by-invoice/000001", admin));
        long tvLine = sale.path("lines").get(0).path("saleItemId").asLong();

        JsonNode refund = data(postJson("/sales/" + sale.path("saleId").asLong() + "/refunds", admin,
                Map.of("lines", List.of(Map.of("saleItemId", tvLine, "quantity", 1)), "method", "CASH", "reason", "Defekt"))
                .andExpect(status().isOk()));
        assertThat(refund.path("totalAmount").decimalValue()).isEqualByComparingTo("16200.00");
        assertThat(refund.path("items").get(0).path("taxAmount").decimalValue()).isEqualByComparingTo("2700.00");

        JsonNode z = data(getJson("/reports/daily", admin));
        assertThat(z.path("refundsCount").asInt()).isEqualTo(1);
        assertThat(z.path("netSales").decimalValue()).isEqualByComparingTo("5400.00");
        assertThat(z.path("vat").get(0).path("taxAmount").decimalValue()).isEqualByComparingTo("900.00");
        assertThat(z.path("printableText").asText()).contains("Kthimet (1): -16200.00").contains("Shitjet neto: 5400.00");
    }

    @Test
    void refundNeedsAnOpenShiftAndAReason() throws Exception {
        String admin = registerSuperAdmin();
        long product = createProduct(admin, "600000000007", "10.00", 5);
        long shiftId = openShift(admin, "0");
        addToCart(admin, product, 1);
        postJson("/sales/checkout", admin, null).andExpect(status().isOk());
        JsonNode sale = data(getJson("/sales/by-invoice/1", admin));
        long saleId = sale.path("saleId").asLong();
        long line = sale.path("lines").get(0).path("saleItemId").asLong();

        postJson("/sales/" + saleId + "/refunds", admin, Map.of("lines", List.of(Map.of("saleItemId", line, "quantity", 1)),
                "method", "CASH", "reason", " ")).andExpect(jsonPath("$.code").value("refund.reasonRequired"));
        postJson("/shifts/" + shiftId + "/close", admin, Map.of("closingCash", "10")).andExpect(status().isOk());
        postJson("/sales/" + saleId + "/refunds", admin, Map.of("lines", List.of(Map.of("saleItemId", line, "quantity", 1)),
                "method", "CASH", "reason", "x")).andExpect(jsonPath("$.code").value("refund.shiftRequired"));
        getJson("/sales/by-invoice/999999", admin).andExpect(status().isBadRequest());
    }

    @Test
    void auditLogRecordsWhoDidWhatAndOnlyTheSuperAdminSeesIt() throws Exception {
        String admin = registerSuperAdmin();
        String manager = createUserAndLogin(admin, "shef", "SUPER_CASHIER");
        postJson("/auth/login", null, Map.of("username", "shef", "password", "wrong")).andExpect(status().isBadRequest());
        postJson("/auth/login", null, Map.of("username", "nobody", "password", "x")).andExpect(status().isBadRequest());
        long product = createProduct(admin, "600000000008", "10.00", 5);
        putJson("/products/" + product, manager, Map.of("name", "Produkt 600000000008", "barcode", "600000000008",
                "price", "12.00", "purchasePrice", "50.00", "taxRate", "20", "stock", 5, "unit", "pcs", "categoryId", 7))
                .andExpect(status().isOk());

        getJson("/audit", manager).andExpect(status().isForbidden());
        JsonNode events = data(getJson("/audit", admin).andExpect(status().isOk()));
        List<String> actions = new java.util.ArrayList<>();
        events.forEach(event -> actions.add(event.path("action").asText()));
        assertThat(actions).contains("USER_CREATED", "LOGIN", "LOGIN_FAILED", "PRODUCT_CREATED", "PRODUCT_UPDATED");

        JsonNode updates = data(getJson("/audit?action=PRODUCT_UPDATED", admin));
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).path("cashierName").asText()).isEqualTo("shef Test");
        assertThat(updates.get(0).path("details").asText()).contains("price 10.00").contains("price 12.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'LOGIN_FAILED'", Integer.class)).isEqualTo(2);
        assertThat(data(getJson("/audit?action=login_failed", admin))).hasSize(2);
    }
}
