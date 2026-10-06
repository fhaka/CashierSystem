package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CheckoutFlowTest extends IntegrationTest {

    @Test
    void fullSaleReducesStockAndBalancesTheShift() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long productId = createProduct(admin, "200000000001", "100.00", 10);
        long shiftId = openShift(cashier, "1000.00");

        postJson("/sales/cart", cashier, Map.of("barcode", "200000000001", "quantity", 3))
                .andExpect(status().isOk());
        getJson("/sales/cart/subtotal", cashier).andExpect(jsonPath("$.data").value(300.00));

        JsonNode receipt = data(postJson("/sales/checkout", cashier, null).andExpect(status().isOk()));
        assertThat(receipt.path("totalAmount").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(receipt.path("items")).hasSize(1);
        assertThat(receipt.path("items").get(0).path("taxAmount").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(stockOf(productId)).isEqualTo(7);

        getJson("/sales/cart", cashier).andExpect(jsonPath("$.data").isEmpty());

        JsonNode shift = data(postJson("/shifts/" + shiftId + "/close", cashier, Map.of("closingCash", "1300.00"))
                .andExpect(status().isOk()));
        assertThat(shift.path("totalSales").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(shift.path("expectedCash").decimalValue()).isEqualByComparingTo("1300.00");
        assertThat(shift.path("difference").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void checkoutRequiresAnOpenShift() throws Exception {
        String admin = registerSuperAdmin();
        long productId = createProduct(admin, "200000000002", "50.00", 5);

        postJson("/sales/cart", admin, Map.of("productId", productId, "quantity", 1)).andExpect(status().isOk());
        postJson("/sales/checkout", admin, null).andExpect(status().isBadRequest());

        assertThat(stockOf(productId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isZero();
    }

    @Test
    void cannotAddMoreThanTheStock() throws Exception {
        String admin = registerSuperAdmin();
        long productId = createProduct(admin, "200000000003", "50.00", 2);

        postJson("/sales/cart", admin, Map.of("productId", productId, "quantity", 2)).andExpect(status().isOk());
        postJson("/sales/cart", admin, Map.of("productId", productId, "quantity", 1)).andExpect(status().isBadRequest());
    }

    @Test
    void unknownBarcodeIsRejected() throws Exception {
        String admin = registerSuperAdmin();
        postJson("/sales/cart", admin, Map.of("barcode", "999999999999", "quantity", 1))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cashierCannotChangePriceButManagerCan() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        String manager = createUserAndLogin(admin, "shef", "SUPER_CASHIER");
        long productId = createProduct(admin, "200000000004", "100.00", 10);

        postJson("/sales/cart", cashier, Map.of("productId", productId, "quantity", 1)).andExpect(status().isOk());
        putJson("/sales/cart/" + productId, cashier, Map.of("quantity", 1, "price", "1.00"))
                .andExpect(status().isForbidden());

        postJson("/sales/cart", manager, Map.of("productId", productId, "quantity", 1)).andExpect(status().isOk());
        putJson("/sales/cart/" + productId, manager, Map.of("quantity", 1, "price", "80.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].price").value(80.00));
    }

    @Test
    void eachCashierHasASeparateCart() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long productId = createProduct(admin, "200000000005", "10.00", 10);

        postJson("/sales/cart", cashier, Map.of("productId", productId, "quantity", 2)).andExpect(status().isOk());

        getJson("/sales/cart", admin).andExpect(jsonPath("$.data").isEmpty());
        getJson("/sales/cart", cashier).andExpect(jsonPath("$.data[0].quantity").value(2));
    }

    @Test
    void cashierSeesOnlyOwnSales() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long productId = createProduct(admin, "200000000006", "10.00", 10);

        openShift(admin, "0");
        postJson("/sales/cart", admin, Map.of("productId", productId, "quantity", 1)).andExpect(status().isOk());
        postJson("/sales/checkout", admin, null).andExpect(status().isOk());

        getJson("/sales", cashier).andExpect(jsonPath("$.data").isEmpty());
        getJson("/sales", admin).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void purchaseInvoiceAddsStockAndUpdatesPrices() throws Exception {
        String admin = registerSuperAdmin();
        long productId = createProduct(admin, "200000000007", "100.00", 4);

        postJson("/purchases", admin, Map.of(
                "invoiceNumber", "F-001",
                "company", "Furnitori Test",
                "invoiceDate", "2026-10-01",
                "items", new Object[] {Map.of(
                        "productId", productId, "quantity", 6, "purchasePrice", "60.00",
                        "sellingPrice", "110.00", "taxRate", "20", "unit", "pcs")}
        )).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalAmount").value(360.00));

        assertThat(stockOf(productId)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id = ?", BigDecimal.class, productId))
                .isEqualByComparingTo("110.00");

        postJson("/purchases", admin, Map.of("invoiceNumber", "f-001", "company", "X", "invoiceDate", "2026-10-01",
                "items", new Object[] {Map.of("productId", productId, "quantity", 1, "purchasePrice", "1",
                        "sellingPrice", "1", "taxRate", "20")}))
                .andExpect(status().isBadRequest());
    }
}
