package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PromotionsAndCustomersTest extends IntegrationTest {

    private long promotion(String token, Map<String, Object> fields) throws Exception {
        return data(postJson("/promotions", token, fields).andExpect(status().isOk())).path("id").asLong();
    }

    private void addToCart(String token, long productId, Object quantity) throws Exception {
        postJson("/sales/cart", token, Map.of("productId", productId, "quantity", quantity)).andExpect(status().isOk());
    }

    private JsonNode summary(String token) throws Exception {
        return data(getJson("/sales/cart/summary", token).andExpect(status().isOk()));
    }

    private static Map<String, Object> pay(String method, String amount) {
        return Map.of("method", method, "currency", "LEK", "amount", amount);
    }

    private long customer(String token, String name, String creditLimit) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("fullName", name, "phone", "069000000"));
        if (creditLimit != null) {
            body.put("creditLimit", creditLimit);
        }
        return data(postJson("/customers", token, body).andExpect(status().isOk())).path("id").asLong();
    }

    @Test
    void percentPromotionLowersTheLineAndItsVat() throws Exception {
        String admin = registerSuperAdmin();
        long cheese = createProduct(admin, "800000000001", "1000.00", 10);
        promotion(admin, Map.of("name", "Djathë -20%", "type", "PERCENT", "discountPercent", "20", "productId", cheese));
        openShift(admin, "0");
        addToCart(admin, cheese, 1);

        JsonNode s = summary(admin);
        assertThat(s.path("promotionDiscount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(s.path("totalAmount").decimalValue()).isEqualByComparingTo("800.00");
        assertThat(s.path("promotions").get(0).path("promotionName").asText()).isEqualTo("Djathë -20%");

        JsonNode receipt = data(postJson("/sales/checkout", admin, null).andExpect(status().isOk()));
        assertThat(receipt.path("items").get(0).path("taxAmount").decimalValue()).isEqualByComparingTo("133.33");
        assertThat(receipt.path("printableReceipt").asText()).contains("Promocion Djathë -20%").contains("Promocionet: -200.00");
        assertThat(jdbc.queryForObject("SELECT promotion_discount FROM sale_items", BigDecimal.class)).isEqualByComparingTo("200.00");
    }

    @Test
    void bestPromotionWinsAndBuyTwoGetOneFree() throws Exception {
        String admin = registerSuperAdmin();
        long soda = createProduct(admin, "800000000002", "100.00", 50);
        promotion(admin, Map.of("name", "Pije -10%", "type", "PERCENT", "discountPercent", "10", "categoryId", 7));
        promotion(admin, Map.of("name", "Bli 2 merr 1", "type", "BUY_X_GET_Y", "buyQuantity", 2, "freeQuantity", 1, "productId", soda));
        addToCart(admin, soda, 7);

        JsonNode s = summary(admin);
        // 7 pieces: two full groups of 3, so 2 are free (200) - better than 10% (70).
        assertThat(s.path("promotionDiscount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(s.path("promotions").get(0).path("promotionName").asText()).isEqualTo("Bli 2 merr 1");
    }

    @Test
    void promotionsOnlyRunOnTheirDaysHoursAndDates() throws Exception {
        String admin = registerSuperAdmin();
        long p = createProduct(admin, "800000000003", "100.00", 50);
        int today = LocalDate.now().getDayOfWeek().getValue();
        String otherDay = String.valueOf(today % 7 + 1);
        promotion(admin, Map.of("name", "Dita tjetër", "type", "PERCENT", "discountPercent", "50", "productId", p, "daysOfWeek", otherDay));
        promotion(admin, Map.of("name", "Skaduar", "type", "PERCENT", "discountPercent", "40", "productId", p,
                "startsOn", LocalDate.now().minusDays(10).toString(), "endsOn", LocalDate.now().minusDays(1).toString()));
        promotion(admin, Map.of("name", "Joaktiv", "type", "PERCENT", "discountPercent", "30", "productId", p, "active", false));
        LocalTime now = LocalTime.now();
        if (now.isAfter(LocalTime.of(0, 5)) && now.isBefore(LocalTime.of(23, 50))) {
            promotion(admin, Map.of("name", "Ora tjetër", "type", "PERCENT", "discountPercent", "60", "productId", p,
                    "startTime", now.plusMinutes(5).withSecond(0).withNano(0).toString(), "endTime", "23:59"));
        }
        promotion(admin, Map.of("name", "Sot", "type", "PERCENT", "discountPercent", "5", "productId", p, "daysOfWeek", String.valueOf(today)));
        addToCart(admin, p, 1);

        JsonNode s = summary(admin);
        assertThat(s.path("promotions")).hasSize(1);
        assertThat(s.path("promotions").get(0).path("promotionName").asText()).isEqualTo("Sot");
        postJson("/promotions", admin, Map.of("name", "X", "type", "PERCENT", "discountPercent", "10", "productId", p, "categoryId", 7))
                .andExpect(jsonPath("$.code").value("promotion.scopeRequired"));
        postJson("/promotions", admin, Map.of("name", "X", "type", "PERCENT", "discountPercent", "10", "productId", p, "daysOfWeek", "8"))
                .andExpect(jsonPath("$.code").value("promotion.invalidDays"));
    }

    @Test
    void manualDiscountNeedsAManagerPinForCashiers() throws Exception {
        String admin = registerSuperAdmin();
        putJson("/users/" + jdbc.queryForObject("SELECT id FROM cashiers WHERE username = 'admin'", Long.class), admin,
                Map.of("fullName", "Admin Test", "username", "admin", "role", "SUPER_ADMIN", "active", true, "approvalPin", "4321"))
                .andExpect(status().isOk());
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long p = createProduct(admin, "800000000004", "1000.00", 10);
        addToCart(cashier, p, 1);

        putJson("/sales/cart/discount", cashier, Map.of("percent", 10)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("approval.required"));
        JsonNode s = data(call(put("/sales/cart/discount").header("X-Approval-Pin", "4321"), cashier, Map.of("percent", 10))
                .andExpect(status().isOk()));
        assertThat(s.path("manualDiscount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(s.path("totalAmount").decimalValue()).isEqualByComparingTo("900.00");
        putJson("/sales/cart/discount", cashier, Map.of("percent", 150)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT approved_by_id FROM audit_events WHERE action = 'MANUAL_DISCOUNT'", Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT id FROM cashiers WHERE username = 'admin'", Long.class));
    }

    @Test
    void promotionManualAndLargePurchaseDiscountsAddUpPerLine() throws Exception {
        String admin = registerSuperAdmin();
        long tv = createProduct(admin, "800000000005", "30000.00", 5);
        long soap = createProduct(admin, "800000000006", "500.00", 5);
        promotion(admin, Map.of("name", "TV -10%", "type", "PERCENT", "discountPercent", "10", "productId", tv));
        openShift(admin, "0");
        addToCart(admin, tv, 1);
        addToCart(admin, soap, 1);
        putJson("/sales/cart/discount", admin, Map.of("percent", 5)).andExpect(status().isOk());

        JsonNode receipt = data(postJson("/sales/checkout", admin, null).andExpect(status().isOk()));
        BigDecimal lines = jdbc.queryForObject("SELECT SUM(line_total) FROM sale_items", BigDecimal.class);
        assertThat(lines).isEqualByComparingTo(receipt.path("totalAmount").decimalValue());
        // 30500 - 3000 promotion = 27500; -5% manual = 26125; over 20000 so -10% = 23512.50
        assertThat(receipt.path("totalAmount").decimalValue()).isEqualByComparingTo("23512.50");
        assertThat(receipt.path("printableReceipt").asText()).contains("Zbritje manuale (5%): -1375.00");
    }

    @Test
    void loyaltyPointsAreEarnedAndSpent() throws Exception {
        String admin = registerSuperAdmin();
        long id = customer(admin, "Klient Besnik", null);
        String card = jdbc.queryForObject("SELECT card_number FROM customers WHERE id = ?", String.class, id);
        assertThat(card).startsWith("99").hasSize(10);
        long p = createProduct(admin, "800000000007", "250.00", 20);
        openShift(admin, "0");

        addToCart(admin, p, 4);
        putJson("/sales/cart/customer", admin, Map.of("cardNumber", card)).andExpect(jsonPath("$.data.customer.fullName").value("Klient Besnik"));
        JsonNode first = data(postJson("/sales/checkout", admin, null).andExpect(status().isOk()));
        assertThat(first.path("pointsEarned").asInt()).isEqualTo(10);
        assertThat(first.path("printableReceipt").asText()).contains("Klienti: Klient Besnik").contains("Pikë të fituara: 10");

        addToCart(admin, p, 1);
        putJson("/sales/cart/customer", admin, Map.of("customerId", String.valueOf(id))).andExpect(status().isOk());
        postJson("/sales/checkout", admin, Map.of("payments", List.of(pay("POINTS", "20"), pay("CASH", "230"))))
                .andExpect(jsonPath("$.code").value("payment.notEnoughPoints"));
        JsonNode second = data(postJson("/sales/checkout", admin, Map.of("payments", List.of(pay("POINTS", "10"), pay("CASH", "240"))))
                .andExpect(status().isOk()));
        assertThat(second.path("pointsEarned").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT points FROM customers WHERE id = ?", Integer.class, id)).isEqualTo(2);
        assertThat(data(getJson("/customers/" + id, admin)).path("transactions")).hasSize(2);
    }

    @Test
    void buyingOnCreditStaysWithinTheLimitAndDebtPaymentsGoIntoTheDrawer() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long noCredit = customer(cashier, "Pa Limit", null);
        postJson("/customers", cashier, Map.of("fullName", "X", "creditLimit", "500")).andExpect(status().isForbidden());
        long neighbour = customer(admin, "Fqinji", "1000");
        long p = createProduct(admin, "800000000008", "300.00", 20);
        long shiftId = openShift(cashier, "1000.00");

        addToCart(cashier, p, 2);
        putJson("/sales/cart/customer", cashier, Map.of("customerId", String.valueOf(noCredit))).andExpect(status().isOk());
        postJson("/sales/checkout", cashier, Map.of("payments", List.of(pay("CREDIT", "600"))))
                .andExpect(jsonPath("$.code").value("payment.creditNotAllowed"));
        putJson("/sales/cart/customer", cashier, Map.of("customerId", String.valueOf(neighbour))).andExpect(status().isOk());
        JsonNode sale = data(postJson("/sales/checkout", cashier, Map.of("payments", List.of(pay("CREDIT", "600")))).andExpect(status().isOk()));
        assertThat(sale.path("customerBalance").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(sale.path("printableReceipt").asText()).contains("Në borxh: 600.00 LEK").contains("Borxhi aktual: 600.00 LEK");

        addToCart(cashier, p, 2);
        putJson("/sales/cart/customer", cashier, Map.of("customerId", String.valueOf(neighbour))).andExpect(status().isOk());
        postJson("/sales/checkout", cashier, Map.of("payments", List.of(pay("CREDIT", "600"))))
                .andExpect(jsonPath("$.message").value("Mund të blihen në borxh edhe vetëm 400.00 LEK"));

        postJson("/customers/" + neighbour + "/payments", cashier, Map.of("amount", "200", "method", "CASH")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT balance FROM customers WHERE id = ?", BigDecimal.class, neighbour)).isEqualByComparingTo("400.00");

        JsonNode shift = data(postJson("/shifts/" + shiftId + "/close", cashier, Map.of("closingCash", "1200.00")));
        assertThat(shift.path("creditSales").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(shift.path("cashIn").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(shift.path("expectedCash").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(shift.path("difference").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void refundOfACreditSaleGoesBackOnTheAccountAndTakesPointsBack() throws Exception {
        String admin = registerSuperAdmin();
        long neighbour = customer(admin, "Fqinji", "5000");
        long p = createProduct(admin, "800000000009", "500.00", 20);
        openShift(admin, "0");
        addToCart(admin, p, 4);
        putJson("/sales/cart/customer", admin, Map.of("customerId", String.valueOf(neighbour))).andExpect(status().isOk());
        postJson("/sales/checkout", admin, Map.of("payments", List.of(pay("CREDIT", "2000")))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT points FROM customers WHERE id = ?", Integer.class, neighbour)).isEqualTo(20);

        JsonNode sale = data(getJson("/sales/by-invoice/1", admin));
        long line = sale.path("lines").get(0).path("saleItemId").asLong();
        postJson("/sales/" + sale.path("saleId").asLong() + "/refunds", admin, Map.of(
                "lines", List.of(Map.of("saleItemId", line, "quantity", 1)), "method", "CREDIT", "reason", "Kthim"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT balance FROM customers WHERE id = ?", BigDecimal.class, neighbour)).isEqualByComparingTo("1500.00");
        assertThat(jdbc.queryForObject("SELECT points FROM customers WHERE id = ?", Integer.class, neighbour)).isEqualTo(15);
        JsonNode z = data(getJson("/reports/daily", admin));
        assertThat(z.path("creditSales").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(z.path("netSales").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(z.path("printableText").asText()).contains("Në borxh: 2000.00");
    }
}
