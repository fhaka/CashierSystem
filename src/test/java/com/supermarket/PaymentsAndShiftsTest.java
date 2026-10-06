package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentsAndShiftsTest extends IntegrationTest {

    private static Map<String, Object> pay(String method, String currency, String amount) {
        return Map.of("method", method, "currency", currency, "amount", amount);
    }

    private void addToCart(String token, long productId, int quantity) throws Exception {
        postJson("/sales/cart", token, Map.of("productId", productId, "quantity", quantity)).andExpect(status().isOk());
    }

    @Test
    void cashPaymentGivesChange() throws Exception {
        String admin = registerSuperAdmin();
        long product = createProduct(admin, "500000000001", "100.00", 10);
        openShift(admin, "0");
        addToCart(admin, product, 3);

        JsonNode receipt = data(postJson("/sales/checkout", admin, Map.of("payments", List.of(pay("CASH", "LEK", "500"))))
                .andExpect(status().isOk()));

        assertThat(receipt.path("paidAmount").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(receipt.path("changeAmount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(receipt.path("printableReceipt").asText())
                .contains(row("Para në dorë", "500.00"))
                .contains(row("Kusuri", "200.00"));
    }

    @Test
    void splitPaymentWithCardAndEuroCash() throws Exception {
        String admin = registerSuperAdmin();
        long product = createProduct(admin, "500000000002", "1000.00", 10);
        openShift(admin, "0");
        addToCart(admin, product, 1);

        JsonNode receipt = data(postJson("/sales/checkout", admin, Map.of("payments", List.of(
                pay("CARD", "LEK", "500"), pay("CASH", "EUR", "10")))).andExpect(status().isOk()));

        assertThat(receipt.path("paidAmount").decimalValue()).isEqualByComparingTo("1457.00");
        assertThat(receipt.path("changeAmount").decimalValue()).isEqualByComparingTo("457.00");
        assertThat(receipt.path("payments")).hasSize(2);
        assertThat(receipt.path("printableReceipt").asText())
                .contains(row("Kartë", "500.00"))
                .contains(row("Para në dorë 10.00 EUR x 95.7", "957.00"));
    }

    @Test
    void notEnoughPaidIsRefusedAndUsesNoInvoiceNumber() throws Exception {
        String admin = registerSuperAdmin();
        long product = createProduct(admin, "500000000003", "100.00", 10);
        openShift(admin, "0");
        addToCart(admin, product, 3);

        postJson("/sales/checkout", admin, Map.of("payments", List.of(pay("CASH", "LEK", "200"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pagesa nuk mjafton. Mungojnë 100.00 LEK"));
        postJson("/sales/checkout", admin, Map.of("payments", List.of(pay("CARD", "LEK", "400"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pagesa me kartë nuk mund të jetë më e madhe se totali"));
        postJson("/sales/checkout", admin, Map.of("payments", List.of(pay("CARD", "EUR", "5"))))
                .andExpect(status().isBadRequest());
        postJson("/sales/checkout", admin, Map.of("payments", List.of(pay("CHEQUE", "LEK", "300"))))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isZero();
        assertThat(stockOf(product)).isEqualTo(10);
        assertThat(data(postJson("/sales/checkout", admin, Map.of("payments", List.of(pay("CASH", "LEK", "300")))))
                .path("invoiceNumber").asText()).isEqualTo("000001");
    }

    @Test
    void exchangeRatesAreSetByManagersAndUsedForPayments() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        getJson("/exchange-rates", cashier).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        putJson("/exchange-rates", cashier, List.of(Map.of("currency", "EUR", "buyRate", "1", "sellRate", "1")))
                .andExpect(status().isForbidden());

        putJson("/exchange-rates", admin, List.of(Map.of("currency", "EUR", "buyRate", "100.00", "sellRate", "99.00")))
                .andExpect(status().isOk());

        long product = createProduct(admin, "500000000004", "1000.00", 10);
        openShift(cashier, "0");
        addToCart(cashier, product, 1);
        JsonNode receipt = data(postJson("/sales/checkout", cashier, Map.of("payments", List.of(pay("CASH", "EUR", "10")))));
        assertThat(receipt.path("changeAmount").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    void shiftExpectsOnlyCashAndCountsDrawerMovements() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long product = createProduct(admin, "500000000005", "100.00", 20);
        long shiftId = openShift(cashier, "1000.00");

        addToCart(cashier, product, 3);
        postJson("/sales/checkout", cashier, Map.of("payments", List.of(pay("CASH", "LEK", "500")))).andExpect(status().isOk());
        addToCart(cashier, product, 4);
        postJson("/sales/checkout", cashier, Map.of("payments", List.of(pay("CARD", "LEK", "400")))).andExpect(status().isOk());
        postJson("/shifts/" + shiftId + "/cash-movements", cashier, Map.of("type", "IN", "amount", "100", "reason", "Monedha për kusur"))
                .andExpect(status().isOk());
        postJson("/shifts/" + shiftId + "/cash-movements", cashier, Map.of("type", "OUT", "amount", "250", "reason", "Pagesë furnitori"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales WHERE shift_id = ?", Integer.class, shiftId)).isEqualTo(2);

        JsonNode shift = data(postJson("/shifts/" + shiftId + "/close", cashier, Map.of("closingCash", "1150.00"))
                .andExpect(status().isOk()));
        assertThat(shift.path("totalSales").decimalValue()).isEqualByComparingTo("700.00");
        assertThat(shift.path("cashSales").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(shift.path("cardSales").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(shift.path("cashIn").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(shift.path("cashOut").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(shift.path("expectedCash").decimalValue()).isEqualByComparingTo("1150.00");
        assertThat(shift.path("difference").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void cashMovementsNeedAReasonAndYourOwnOpenShift() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long shiftId = openShift(cashier, "0");

        postJson("/shifts/" + shiftId + "/cash-movements", cashier, Map.of("type", "OUT", "amount", "10", "reason", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Shkruani arsyen e lëvizjes së parave"));
        postJson("/shifts/" + shiftId + "/cash-movements", admin, Map.of("type", "OUT", "amount", "10", "reason", "x"))
                .andExpect(status().isForbidden());
        postJson("/shifts/" + shiftId + "/cash-movements", cashier, Map.of("type", "SIDEWAYS", "amount", "10", "reason", "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cashierSeesTheShiftReportOnlyAfterClosing() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long product = createProduct(admin, "500000000006", "100.00", 20);
        long shiftId = openShift(cashier, "500.00");
        addToCart(cashier, product, 2);
        postJson("/sales/checkout", cashier, null).andExpect(status().isOk());

        getJson("/shifts/" + shiftId + "/report", cashier).andExpect(status().isForbidden());
        JsonNode managerView = data(getJson("/shifts/" + shiftId + "/report", admin).andExpect(status().isOk()));
        assertThat(managerView.path("expectedCash").decimalValue()).isEqualByComparingTo("700.00");
        assertThat(managerView.path("printableText").asText()).contains("RAPORTI X - TURNI " + shiftId + "\n");

        postJson("/shifts/" + shiftId + "/close", cashier, Map.of("closingCash", "690.00")).andExpect(status().isOk());
        JsonNode cashierView = data(getJson("/shifts/" + shiftId + "/report", cashier).andExpect(status().isOk()));
        assertThat(cashierView.path("totalSales").decimalValue()).isEqualByComparingTo("200.00");
    }

    @Test
    void dailyZReportCoversAllTills() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long product = createProduct(admin, "500000000007", "100.00", 20);
        openShift(admin, "0");
        openShift(cashier, "0");
        addToCart(admin, product, 1);
        postJson("/sales/checkout", admin, Map.of("payments", List.of(pay("CARD", "LEK", "100")))).andExpect(status().isOk());
        addToCart(cashier, product, 2);
        postJson("/sales/checkout", cashier, Map.of("payments", List.of(pay("CASH", "EUR", "5")))).andExpect(status().isOk());

        getJson("/reports/daily", cashier).andExpect(status().isForbidden());
        JsonNode report = data(getJson("/reports/daily", admin).andExpect(status().isOk()));

        assertThat(report.path("type").asText()).isEqualTo("Z");
        assertThat(report.path("salesCount").asInt()).isEqualTo(2);
        assertThat(report.path("totalSales").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(report.path("cardSales").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(report.path("cashReceived").decimalValue()).isEqualByComparingTo("478.50");
        assertThat(report.path("changeGiven").decimalValue()).isEqualByComparingTo("278.50");
        assertThat(report.path("cashByCurrency").get(0).path("currency").asText()).isEqualTo("EUR");
        assertThat(report.path("byCashier")).hasSize(2);
        assertThat(report.path("vat").get(0).path("taxAmount").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(report.path("printableText").asText()).contains("RAPORTI Z").contains("SIPAS ARKËTARIT").contains(row("Hyrje parash", "0.00"));
    }
}
