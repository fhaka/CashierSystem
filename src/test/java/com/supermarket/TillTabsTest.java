package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TillTabsTest extends IntegrationTest {

    private ResultActions onTab(MockHttpServletRequestBuilder request, String token, int tab, Object body) throws Exception {
        return call(request.header("X-Cart-Slot", tab), token, body);
    }

    @Test
    void eachTabIsItsOwnCustomerAndPayingOneLeavesTheOthers() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long bread = createProduct(admin, "960000000001", "80.00", 20);
        long milk = createProduct(admin, "960000000002", "120.00", 20);
        openShift(cashier, "0");

        // Customer A in tab 1 goes back for something; customer B is served in tab 2.
        onTab(post("/sales/cart"), cashier, 1, Map.of("productId", bread, "quantity", 3)).andExpect(status().isOk());
        onTab(post("/sales/cart"), cashier, 2, Map.of("productId", milk, "quantity", 1)).andExpect(status().isOk());
        assertThat(data(onTab(get("/sales/cart"), cashier, 2, null))).hasSize(1);

        JsonNode tabs = data(getJson("/sales/cart/tabs", cashier).andExpect(status().isOk()));
        assertThat(tabs).hasSize(3);
        assertThat(tabs.get(0).path("total").decimalValue()).isEqualByComparingTo("240.00");
        assertThat(tabs.get(1).path("total").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(tabs.get(2).path("lines").asInt()).isZero();

        JsonNode receipt = data(onTab(post("/sales/checkout"), cashier, 2, null).andExpect(status().isOk()));
        assertThat(receipt.path("totalAmount").decimalValue()).isEqualByComparingTo("120.00");
        // A comes back: tab 1 is still there, untouched, and gets one more item.
        onTab(post("/sales/cart"), cashier, 1, Map.of("productId", milk, "quantity", 1)).andExpect(status().isOk());
        JsonNode first = data(onTab(post("/sales/checkout"), cashier, 1, null).andExpect(status().isOk()));
        assertThat(first.path("totalAmount").decimalValue()).isEqualByComparingTo("360.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isEqualTo(2);

        // No tab given means tab 1; there is no tab 4.
        postJson("/sales/cart", cashier, Map.of("productId", bread, "quantity", 1)).andExpect(status().isOk());
        assertThat(data(onTab(get("/sales/cart"), cashier, 1, null))).hasSize(1);
        onTab(post("/sales/cart"), cashier, 4, Map.of("productId", bread, "quantity", 1))
                .andExpect(jsonPath("$.code").value("cart.invalidTab"));
    }

    @Test
    void theStockCheckOfOneTabDoesNotReserveStockForAnother() throws Exception {
        String admin = registerSuperAdmin();
        long last = createProduct(admin, "960000000003", "50.00", 1);
        openShift(admin, "0");
        onTab(post("/sales/cart"), admin, 1, Map.of("productId", last, "quantity", 1)).andExpect(status().isOk());
        onTab(post("/sales/cart"), admin, 2, Map.of("productId", last, "quantity", 1)).andExpect(status().isOk());
        // Whoever pays first gets the last piece; the other tab is told at checkout.
        onTab(post("/sales/checkout"), admin, 2, null).andExpect(status().isOk());
        onTab(post("/sales/checkout"), admin, 1, null).andExpect(status().isBadRequest());
    }

    @Test
    void clearingATabNeedsAPinAndClosingTheTillWithUnsoldTabsNeedsAConfirmation() throws Exception {
        String admin = registerSuperAdmin();
        putJson("/users/" + jdbc.queryForObject("SELECT id FROM cashiers WHERE username = 'admin'", Long.class), admin,
                Map.of("fullName", "Admin Test", "username", "admin", "role", "SUPER_ADMIN", "active", true, "approvalPin", "4321"))
                .andExpect(status().isOk());
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long p = createProduct(admin, "960000000004", "100.00", 10);
        long shiftId = openShift(cashier, "0");
        onTab(post("/sales/cart"), cashier, 1, Map.of("productId", p, "quantity", 2)).andExpect(status().isOk());
        onTab(post("/sales/cart"), cashier, 3, Map.of("productId", p, "quantity", 1)).andExpect(status().isOk());

        // "Pastro faturën" on tab 3: a manager's PIN, and only tab 3 is emptied.
        onTab(delete("/sales/cart"), cashier, 3, null).andExpect(jsonPath("$.code").value("approval.required"));
        onTab(delete("/sales/cart").header("X-Approval-Pin", "4321"), cashier, 3, null).andExpect(status().isOk());
        assertThat(data(onTab(get("/sales/cart"), cashier, 3, null))).isEmpty();
        assertThat(data(onTab(get("/sales/cart"), cashier, 1, null))).hasSize(1);

        postJson("/shifts/" + shiftId + "/close", cashier, Map.of("closingCash", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("cart.unsoldItems"));
        assertThat(jdbc.queryForObject("SELECT status FROM shifts WHERE id = ?", String.class, shiftId)).isEqualTo("OPEN");

        postJson("/shifts/" + shiftId + "/close", cashier, Map.of("closingCash", "0", "discardCarts", true)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM carts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT details FROM audit_events WHERE action = 'CARTS_DISCARDED'", String.class))
                .contains("tab 1").contains("Produkt 960000000004 x 2").contains("200.00 LEK");
        assertThat(stockOf(p)).isEqualTo(10);
    }

    @Test
    void aParkedCartComesBackIntoTheTabItIsResumedIn() throws Exception {
        String admin = registerSuperAdmin();
        long p = createProduct(admin, "960000000005", "100.00", 10);
        onTab(post("/sales/cart"), admin, 2, Map.of("productId", p, "quantity", 4)).andExpect(status().isOk());
        long cartId = data(onTab(post("/sales/cart/park"), admin, 2, Map.of("label", "Zonja me karrocë"))).path("id").asLong();
        assertThat(data(onTab(get("/sales/cart"), admin, 2, null))).isEmpty();

        onTab(post("/sales/carts/" + cartId + "/resume"), admin, 3, null).andExpect(status().isOk());
        assertThat(data(onTab(get("/sales/cart"), admin, 3, null)).get(0).path("quantity").asInt()).isEqualTo(4);
    }
}
